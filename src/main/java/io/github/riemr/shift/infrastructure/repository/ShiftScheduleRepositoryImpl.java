package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.ShiftScheduleRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.*;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartmentSkill;
import io.github.riemr.shift.infrastructure.mapper.*;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartment;
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
    private final WorkDemandQuarterMapper     workDemandQuarterMapper;
    private final EmployeeDepartmentMapper    employeeDepartmentMapper;
    private final EmployeeDepartmentSkillMapper employeeDepartmentSkillMapper;
    private final DepartmentMasterMapper departmentMasterMapper;

    /*
     * buildEmptyAssignments() で生成する一時レコード用の負 ID 採番器。
     * 永続化時に全て INSERT するため、他と重複しなければ何でも良い。
     */
    private static final AtomicLong TEMP_ID_SEQ = new AtomicLong(-1L);

    // ============================================================================
    // public API
    // ============================================================================

    @Override
    public ShiftSchedule fetchShiftSchedule(LocalDate month, String storeCode, String departmentCode) {
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
        List<EmployeeDepartmentSkill> deptSkills = departmentCode != null
                ? employeeDepartmentSkillMapper.selectByDepartment(departmentCode)
                : java.util.Collections.emptyList();

        // ウォームスタート用の前回結果は「前サイクル」範囲で取得
        List<RegisterAssignment> previous = assignmentMapper.selectByMonth(
                cycleStart.minusMonths(1),
                cycleStart);

        // 2. 未割当 Assignment 生成
        List<ShiftAssignmentPlanningEntity> emptyAssignments = new ArrayList<>();
        // 部門のレジフラグに応じてレジ需要を含めるか決定
        boolean isRegisterDepartment = false;
        if (departmentCode != null && !departmentCode.isBlank()) {
            // Treat department "520" as the Register department (legacy convention)
            if ("520".equalsIgnoreCase(departmentCode)) {
                isRegisterDepartment = true;
            } else {
                var dept = departmentMasterMapper.selectByCode(departmentCode);
                isRegisterDepartment = (dept != null && Boolean.TRUE.equals(dept.getIsRegister()));
            }
        }

        // Filter employees by department only when it's NOT a register department
        if (departmentCode != null && !departmentCode.isBlank() && !isRegisterDepartment) {
            var edList = employeeDepartmentMapper.selectByDepartment(departmentCode);
            java.util.Set<String> allowed = edList.stream().map(EmployeeDepartment::getEmployeeCode).collect(Collectors.toSet());
            employees = employees.stream().filter(e -> allowed.contains(e.getEmployeeCode())).toList();
        }

        // 後方互換: 部門未指定の場合はレジ需要を含める（従来の挙動）
        if (departmentCode == null || departmentCode.isBlank()) {
            isRegisterDepartment = true;
        }

        // レジ部門ならレジ需要も含める
        if (isRegisterDepartment) {
            emptyAssignments.addAll(buildEmptyAssignments(demands, registers, departmentCode));
        }

        // 指定部門の非レジ作業需要を含める（レジ部門か否かに関わらず）
        List<io.github.riemr.shift.infrastructure.persistence.entity.WorkDemandQuarter> workDemands = List.of();
        if (departmentCode != null && !departmentCode.isBlank()) {
            workDemands = workDemandQuarterMapper.selectByMonth(storeCode, departmentCode, cycleStart, cycleEnd);
            emptyAssignments.addAll(buildEmptyWorkAssignments(workDemands, departmentCode));
        }

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
        schedule.setDepartmentCode(departmentCode);
        schedule.setWorkDemandList(workDemands);
        schedule.setEmployeeDepartmentSkillList(deptSkills);
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
                                                                     List<Register> registerList,
                                                                     String departmentCode) {
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
                ent.setDepartmentCode(departmentCode);
                ent.setWorkKind(io.github.riemr.shift.optimization.entity.WorkKind.REGISTER_OP);
                result.add(ent);
            }
        }
        
        return result;
    }

    private List<ShiftAssignmentPlanningEntity> buildEmptyWorkAssignments(
            List<io.github.riemr.shift.infrastructure.persistence.entity.WorkDemandQuarter> demandList,
            String departmentCode) {
        List<ShiftAssignmentPlanningEntity> result = new ArrayList<>();
        for (var d : demandList) {
            LocalDate demandDate = d.getDemandDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDateTime start = LocalDateTime.of(demandDate, d.getSlotTime());
            LocalDateTime end = start.plusMinutes(15);
            for (int i = 0; i < d.getRequiredUnits(); i++) {
                // Reuse RegisterAssignment as time container with null registerNo
                RegisterAssignment sa = new RegisterAssignment();
                sa.setAssignmentId(TEMP_ID_SEQ.getAndDecrement());
                sa.setStoreCode(d.getStoreCode());
                sa.setEmployeeCode(null);
                sa.setRegisterNo(null);
                sa.setStartAt(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()));
                sa.setEndAt(Date.from(end.atZone(ZoneId.systemDefault()).toInstant()));
                sa.setCreatedBy("system");

                ShiftAssignmentPlanningEntity ent = new ShiftAssignmentPlanningEntity(sa);
                ent.setShiftId(sa.getAssignmentId());
                ent.setDepartmentCode(departmentCode);
                ent.setWorkKind(io.github.riemr.shift.optimization.entity.WorkKind.DEPARTMENT_TASK);
                ent.setTaskCode(d.getTaskCode());
                result.add(ent);
            }
        }
        return result;
    }
}
