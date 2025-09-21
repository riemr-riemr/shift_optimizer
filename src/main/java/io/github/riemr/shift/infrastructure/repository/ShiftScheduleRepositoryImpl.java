package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.ShiftScheduleRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.*;
import io.github.riemr.shift.infrastructure.mapper.*;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ShiftScheduleRepositoryImpl implements ShiftScheduleRepository {

    // --- MyBatis mappers (既存) --------------------------------------------------
    private final EmployeeMapper              employeeMapper;
    private final EmployeeRegisterSkillMapper skillMapper;
    private final RegisterMapper              registerMapper;
    private final RegisterTypeMapper          registerTypeMapper;
    private final RegisterDemandQuarterMapper demandMapper;
    private final EmployeeRequestMapper       requestMapper;
    private final ConstraintMasterMapper      constraintMasterMapper;
    private final ConstraintSettingMapper     constraintSettingMapper;
    private final StoreMapper                 storeMapper;
    private final RegisterAssignmentMapper    assignmentMapper; // 前回結果 (warm‑start) 用

    /*
     * buildEmptyAssignments() で生成する一時レコード用の負 ID 採番器。
     * 永続化時に全て INSERT するため、他と重複しなければ何でも良い。
     */
    private static final AtomicLong TEMP_ID_SEQ = new AtomicLong(-1L);

    // ============================================================================
    // public API
    // ============================================================================

    @Override
    public ShiftSchedule fetchShiftSchedule(LocalDate month, String storeCode) {
        // 呼び出し側は「サイクル開始日」を渡してくる前提
        LocalDate cycleStart = month;
        LocalDate cycleEnd   = cycleStart.plusMonths(1); // 半開区間 [start, end)

        // 1. 必要なマスタ／トランザクションデータを取得
        List<Employee> employees = (storeCode == null)
                ? employeeMapper.selectAll()
                : employeeMapper.selectByStoreCode(storeCode);

        List<Register> registers = registerMapper.selectAll();

        // 需要は「サイクル開始日〜+1ヶ月」で取得（店舗指定があれば絞り込み）
        var demandEx = new RegisterDemandQuarterExample();
        var dc = demandEx.createCriteria();
        dc.andDemandDateGreaterThanOrEqualTo(java.sql.Date.valueOf(cycleStart));
        dc.andDemandDateLessThan(java.sql.Date.valueOf(cycleEnd));
        if (storeCode != null) {
            dc.andStoreCodeEqualTo(storeCode);
        }
        demandEx.setOrderByClause("store_code, demand_date, slot_time");
        List<RegisterDemandQuarter> demandsRaw = demandMapper.selectByExample(demandEx);
        
        // 重複行（同一 store/date/slot）が存在する場合があるため集約（最大値採用）
        Map<String, RegisterDemandQuarter> dedup = new LinkedHashMap<>();
        for (RegisterDemandQuarter d : demandsRaw) {
            String key = d.getStoreCode() + "|" + d.getDemandDate() + "|" + d.getSlotTime();
            RegisterDemandQuarter prev = dedup.get(key);
            if (prev == null) {
                dedup.put(key, d);
            } else {
                // requiredUnits は大きい方を優先（誤重複の合算を避ける）
                if (d.getRequiredUnits() != null && prev.getRequiredUnits() != null) {
                    if (d.getRequiredUnits() > prev.getRequiredUnits()) {
                        dedup.put(key, d);
                    }
                }
            }
        }
        List<RegisterDemandQuarter> demands = new ArrayList<>(dedup.values());

        // 希望は日付範囲APIを利用
        List<EmployeeRequest> requests = requestMapper.selectByDateRange(cycleStart, cycleEnd);

        List<ConstraintMaster> settings = constraintMasterMapper.selectAll();

        // ウォームスタート用の前回結果は「前サイクル」範囲で取得
        List<RegisterAssignment> previous = assignmentMapper.selectByMonth(
                cycleStart.minusMonths(1),
                cycleStart);

        // 2. 未割当 Assignment 生成
        List<ShiftAssignmentPlanningEntity> emptyAssignments = buildEmptyAssignments(demands, registers);

        // 3. ドメインモデル組み立て
        ShiftSchedule schedule = new ShiftSchedule();
        schedule.setEmployeeList(employees);
        schedule.setRegisterList(registers);
        schedule.setDemandList(demands);
        schedule.setEmployeeRequestList(requests);
        schedule.setConstraintMasterList(settings);
        schedule.setPreviousAssignmentList(previous);
        schedule.setAssignmentList(emptyAssignments);
        schedule.setMonth(cycleStart);
        schedule.setStoreCode(storeCode);
        return schedule;
    }

    // ============================================================================
    // 初期アサイン生成
    // ============================================================================

    /**
     * {@link RegisterDemandQuarter#getRequiredUnits()} で示された台数文だけ、
     * 15 分スロットごとに {@link ShiftAssignmentPlanningEntity} を生成します。
     * <p>
     * <ul>
     *     <li>Employee は <code>null</code> で初期化し、OptaPlanner が割り当て</li>
     *     <li>registerNo は <b>レジ番号の若い順</b> で、<b>SEMI → NORMAL</b> の優先で設定</li>
     * </ul>
     */
    private List<ShiftAssignmentPlanningEntity> buildEmptyAssignments(List<RegisterDemandQuarter> demandList,
                                                                     List<Register> registerList) {
        // 店舗毎のレジを [SEMI 優先] & [register_no 昇順] ソートで保持
        Map<String, List<Register>> registerMap = registerList.stream()
                .collect(Collectors.groupingBy(Register::getStoreCode, Collectors.collectingAndThen(Collectors.toList(), list -> {
                    Comparator<Register> typeThenNo = Comparator
                            .comparing((Register r) -> "SEMI".equalsIgnoreCase(r.getRegisterType()) ? 0 : 1)
                            .thenComparing(Register::getRegisterNo);
                    return list.stream().sorted(typeThenNo).toList();
                })));

        List<ShiftAssignmentPlanningEntity> result = new ArrayList<>();

        for (RegisterDemandQuarter d : demandList) {
            LocalDate demandDate = d.getDemandDate().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDateTime start = LocalDateTime.of(demandDate, d.getSlotTime());
            LocalDateTime end   = start.plusMinutes(15);

            List<Register> candidates = registerMap.getOrDefault(d.getStoreCode(), List.of());
            if (candidates.isEmpty()) {
                log.warn("No registers defined for store {} – assignments will have null registerNo", d.getStoreCode());
            }

            for (int i = 0; i < d.getRequiredUnits(); i++) {
                // レジを必要台数分だけ順番に取得（足りない場合は null）
                Integer registerNo = i < candidates.size() ? candidates.get(i).getRegisterNo() : null;

                // ---- RegisterAssignment 組み立て ----
                RegisterAssignment sa = new RegisterAssignment();
                sa.setAssignmentId(TEMP_ID_SEQ.getAndDecrement());
                sa.setStoreCode(d.getStoreCode());
                sa.setEmployeeCode(null);           // 未割当
                sa.setRegisterNo(registerNo);       // 今回の修正ポイント
                sa.setStartAt(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()));
                sa.setEndAt(Date.from(end.atZone(ZoneId.systemDefault()).toInstant()));
                sa.setCreatedBy("system");

                ShiftAssignmentPlanningEntity ent = new ShiftAssignmentPlanningEntity(sa);
                ent.setShiftId(sa.getAssignmentId());
                result.add(ent);
            }
        }
        
        return result;
    }
}
