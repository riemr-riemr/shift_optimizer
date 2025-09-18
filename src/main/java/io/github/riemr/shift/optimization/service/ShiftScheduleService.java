package io.github.riemr.shift.optimization.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeRegisterSkillMapper;
import org.optaplanner.core.api.solver.SolverJob;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.api.solver.SolverStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.riemr.shift.application.dto.ShiftAssignmentMonthlyView;
import io.github.riemr.shift.application.dto.ShiftAssignmentView;
import io.github.riemr.shift.application.dto.SolveStatusDto;
import io.github.riemr.shift.application.dto.SolveTicket;
import io.github.riemr.shift.application.dto.ShiftAssignmentSaveRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterAssignment;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment;
import io.github.riemr.shift.infrastructure.mapper.RegisterAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import io.github.riemr.shift.application.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OptaPlanner による月次シフト計算を制御するサービス。
 * <ul>
 *   <li>月 (yyyy‑MM) をキーに非同期ジョブを起動</li>
 *   <li>進捗状況をポーリング API 経由で公開</li>
 *   <li>計算終了後、最善解を DTO に変換して返却</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ShiftScheduleService {

    /* === Collaborators === */
    private final SolverManager<ShiftSchedule, ProblemKey> solverManager;
    private final ShiftScheduleRepository repository;
    private final RegisterAssignmentMapper registerAssignmentMapper;
    private final ShiftAssignmentMapper shiftAssignmentMapper;
    private final EmployeeRegisterSkillMapper employeeRegisterSkillMapper;
    private final EmployeeMapper employeeMapper;

    /* === Settings === */
    @Value("${shift.solver.spent-limit:PT2M}") // ISO‑8601 Duration (default 2 minutes)
    private Duration spentLimit;

    /* === Runtime State === */
    private final Map<ProblemKey, Instant> startMap = new ConcurrentHashMap<>();
    private final Map<ProblemKey, SolverJob<ShiftSchedule, ProblemKey>> jobMap = new ConcurrentHashMap<>();
    private final Map<ProblemKey, String> currentPhaseMap = new ConcurrentHashMap<>(); // 現在のフェーズ

    /* ===================================================================== */
    /* Public API                                                            */
    /* ===================================================================== */

    /**
     * 月次シフト計算を非同期で開始。
     * 既に同じ月のジョブが走っている場合はそのステータスを再利用する。
     */
    @Transactional
    public SolveTicket startSolveMonth(LocalDate month) {
        return startSolveMonth(month, null);
    }

    /**
     * 月次シフト計算を非同期で開始（店舗指定あり）。
     * 既に同じ月のジョブが走っている場合はそのステータスを再利用する。
     */
    @Transactional
    public SolveTicket startSolveMonth(LocalDate month, String storeCode) {
        long problemId = toProblemId(month);
        ProblemKey key = new ProblemKey(java.time.YearMonth.from(month), storeCode);

        // 既存ジョブならチケット再発行
        if (jobMap.containsKey(key)) {
            Instant started = startMap.get(key);
            return new SolveTicket(problemId,
                    started.toEpochMilli(),
                    started.plus(spentLimit).toEpochMilli());
        }

        // Solver 起動 (listen)
        SolverJob<ShiftSchedule, ProblemKey> job = solverManager.solveAndListen(
                key,
                this::loadProblem,
                bestSolution -> {
                    // フェーズ情報のみ更新
                    updatePhase(key, bestSolution);
                    persistResult(bestSolution);
                },
                this::onError);
        jobMap.put(key, job);

        // 進捗メタ情報
        Instant start = Instant.now();
        startMap.put(key, start);

        return new SolveTicket(problemId,
                start.toEpochMilli(),
                start.plus(spentLimit).toEpochMilli());
    }

    /** 進捗バー用ステータス */
    public SolveStatusDto getStatus(Long problemId, String storeCode) {
        ProblemKey key = new ProblemKey(java.time.YearMonth.of((int)(problemId/100), (int)(problemId%100)), storeCode);
        SolverStatus status = solverManager.getSolverStatus(key);
        Instant began = startMap.get(key);
        if (began == null) return new SolveStatusDto("UNKNOWN", 0, 0, "未開始");

        long now = Instant.now().toEpochMilli();
        long finish = began.plus(spentLimit).toEpochMilli();
        
        // 時間ベースの進捗計算
        int pct = (int) Math.min(100, ((now - began.toEpochMilli()) * 100) / (finish - began.toEpochMilli()));
        
        // フェーズ情報を取得
        String currentPhase = currentPhaseMap.getOrDefault(key, "初期化中");
        
        if (status == SolverStatus.NOT_SOLVING) {
            pct = 100;
            currentPhase = "完了";
            // 後始末
            jobMap.remove(key);
            startMap.remove(key);
            currentPhaseMap.remove(key);
        }

        return new SolveStatusDto(status.name(), pct, finish, currentPhase);
    }

    /** 計算終了後の最終解をフロント用 DTO に変換して返す */
    public List<ShiftAssignmentView> fetchResult(Long problemId, String storeCode) {
        ProblemKey key = new ProblemKey(java.time.YearMonth.of((int)(problemId/100), (int)(problemId%100)), storeCode);
        SolverJob<ShiftSchedule, ProblemKey> job = jobMap.get(key);
        if (job == null) return List.of();

        try {
            ShiftSchedule solved = job.getFinalBestSolution();
            return solved.getAssignmentList().stream()
                    .map(a -> new ShiftAssignmentView(
                            a.getOrigin().getStartAt().toString(),
                            a.getOrigin().getEndAt().toString(),
                            a.getOrigin().getRegisterNo(),
                            Optional.ofNullable(a.getAssignedEmployee())
                                    .map(emp -> emp.getEmployeeCode())
                                    .orElse("-"),
                            Optional.ofNullable(a.getAssignedEmployee())
                                    .map(emp -> emp.getEmployeeName())
                                    .orElse("-")))
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to retrieve solution", e);
        }
    }

    /** 月別シフト取得 - レジアサインメント表示 */
    public List<ShiftAssignmentMonthlyView> fetchAssignmentsByMonth(LocalDate anyDayInMonth, String storeCode) {
        LocalDate from = anyDayInMonth.withDayOfMonth(1);
        LocalDate to   = from.plusMonths(1);  // 翌月 1 日 (半開区間)

        List<RegisterAssignment> assignments = registerAssignmentMapper.selectByMonth(from, to)
                .stream()
                .filter(a -> storeCode == null || storeCode.equals(a.getStoreCode()))
                .toList();

        // 事前に従業員名を一括取得
        Map<String, String> nameMap = (storeCode != null ? employeeMapper.selectByStoreCode(storeCode) : employeeMapper.selectAll())
                .stream().collect(Collectors.toMap(e -> e.getEmployeeCode(), e -> e.getEmployeeName(), (a,b)->a));

        return assignments.stream()
                .map(a -> new ShiftAssignmentMonthlyView(
                        toLocalDateTime(a.getStartAt()),
                        toLocalDateTime(a.getEndAt()),
                        a.getRegisterNo(),
                        a.getEmployeeCode(),
                        Optional.ofNullable(a.getEmployeeCode()).map(code -> nameMap.getOrDefault(code, "")).orElse("")
                ))
                .toList();
    }

    /** 月別出勤時間取得 - シフトアサインメント表示 */
    public List<ShiftAssignmentMonthlyView> fetchShiftsByMonth(LocalDate anyDayInMonth, String storeCode) {
        LocalDate from = anyDayInMonth.withDayOfMonth(1);
        LocalDate to   = from.plusMonths(1);  // 翌月 1 日 (半開区間)

        List<ShiftAssignment> shifts = shiftAssignmentMapper.selectByMonth(from, to)
                .stream()
                .filter(s -> storeCode == null || storeCode.equals(s.getStoreCode()))
                .toList();

        Map<String, String> nameMap = (storeCode != null ? employeeMapper.selectByStoreCode(storeCode) : employeeMapper.selectAll())
                .stream().collect(Collectors.toMap(e -> e.getEmployeeCode(), e -> e.getEmployeeName(), (a,b)->a));

        return shifts.stream()
                .map(s -> new ShiftAssignmentMonthlyView(
                        toLocalDateTime(s.getStartAt()),
                        toLocalDateTime(s.getEndAt()),
                        null, // No register for shift assignments
                        s.getEmployeeCode(),
                        Optional.ofNullable(s.getEmployeeCode()).map(code -> nameMap.getOrDefault(code, "")).orElse("")
                ))
                .toList();
    }

    public List<ShiftAssignmentView> fetchAssignmentsByDate(LocalDate date, String storeCode) {
        List<RegisterAssignment> assignments = registerAssignmentMapper.selectByDate(date, date.plusDays(1))
                .stream()
                .filter(a -> storeCode == null || storeCode.equals(a.getStoreCode()))
                .toList();
        Map<String, String> nameMap = (storeCode != null ? employeeMapper.selectByStoreCode(storeCode) : employeeMapper.selectAll())
                .stream().collect(Collectors.toMap(e -> e.getEmployeeCode(), e -> e.getEmployeeName(), (a,b)->a));
        return assignments.stream()
                .map(a -> new ShiftAssignmentView(
                        Optional.ofNullable(a.getStartAt())
                                .map(d -> d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                                .orElse(""),
                        Optional.ofNullable(a.getEndAt())
                                .map(d -> d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                                .orElse(""),
                        a.getRegisterNo(),
                        Optional.ofNullable(a.getEmployeeCode()).orElse(""),
                        Optional.ofNullable(a.getEmployeeCode()).map(code -> nameMap.getOrDefault(code, "")).orElse("")
                ))
                .toList();
    }

    @Transactional
    public void saveShiftAssignmentChanges(ShiftAssignmentSaveRequest request) {
        LocalDate date = request.date();
        
        for (var change : request.changes()) {
            String employeeCode = change.employeeCode();
            String timeStr = change.time(); // "HH:mm" format
            String currentRegister = change.current();
            
            // 時刻文字列をLocalTimeに変換
            String[] timeParts = timeStr.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            
            // 15分スロットの開始・終了時間を計算
            LocalDateTime startDateTime = date.atTime(hour, minute);
            LocalDateTime endDateTime = startDateTime.plusMinutes(15);
            
            Date startAt = Date.from(startDateTime.atZone(ZoneId.systemDefault()).toInstant());
            Date endAt = Date.from(endDateTime.atZone(ZoneId.systemDefault()).toInstant());
            
            // 既存の割り当てを削除
            registerAssignmentMapper.deleteByEmployeeCodeAndTimeRange(employeeCode, startAt, endAt);
            
            // 新しい割り当てを作成（currentRegisterが空でない場合）
            if (currentRegister != null && !currentRegister.trim().isEmpty()) {
                RegisterAssignment assignment = new RegisterAssignment();
                assignment.setStoreCode(request.storeCode());
                assignment.setEmployeeCode(employeeCode);
                assignment.setRegisterNo(Integer.parseInt(currentRegister));
                assignment.setStartAt(startAt);
                assignment.setEndAt(endAt);
                assignment.setCreatedBy("manual_edit");
                
                registerAssignmentMapper.insert(assignment);
            }
        }
        
        log.info("Saved {} shift assignment changes for date {}", request.changes().size(), date);
    }

    /* ===================================================================== */
    /* Callback for solveAndListen                                            */
    /* ===================================================================== */

    /**
     * Solver が最初に呼び出す問題生成関数。
     * problemId は yyyyMM の long 値で渡される。
     */
    private ShiftSchedule loadProblem(ProblemKey key) {
        LocalDate month = LocalDate.of(key.getMonth().getYear(), key.getMonth().getMonthValue(), 1);
        ShiftSchedule unsolved = repository.fetchShiftSchedule(month, key.getStoreCode());
        unsolved.setEmployeeRegisterSkillList(employeeRegisterSkillMapper.selectByExample(null));
        // Repository 側で必要なフィールドをセット済みだが、問題 ID だけはここで上書きしておく
        unsolved.setProblemId(toProblemId(month));
        if (unsolved.getAssignmentList() == null) {
            unsolved.setAssignmentList(new ArrayList<>());
        }
        log.info("Loaded unsolved problem for {} store {} ({} assignments)", month, unsolved.getStoreCode(), unsolved.getAssignmentList().size());
        return unsolved;
    }

    /**
     * 新しいベスト解が到着する度に呼び出され、DB に永続化する。
     * shift_assignmentテーブルには出勤時間を、register_assignmentテーブルにはレジアサイン時間を保存する。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    private void persistResult(ShiftSchedule best) {
        // 既存データをクリア
        LocalDate month = best.getMonth();
        LocalDate from = month.withDayOfMonth(1);
        LocalDate to = from.plusMonths(1);
        String store = best.getStoreCode();
        if (store != null) {
            registerAssignmentMapper.deleteByMonthAndStore(from, to, store);
            shiftAssignmentMapper.deleteByMonthAndStore(from, to, store);
        } else {
            // 後方互換: storeCode が無い場合は従来の削除（非推奨）
            registerAssignmentMapper.deleteByProblemId(best.getProblemId());
            shiftAssignmentMapper.deleteByProblemId(best.getProblemId());
        }

        // Group by employee and date for shift assignments (work time)
        Map<String, List<ShiftAssignmentPlanningEntity>> shiftsByEmployeeAndDate = best.getAssignmentList().stream()
                .filter(a -> a.getAssignedEmployee() != null)
                .collect(Collectors.groupingBy(a -> a.getAssignedEmployee().getEmployeeCode() + "@" + a.getShiftDate().toString()));

        List<ShiftAssignment> shiftAssignments = new ArrayList<>();
        for (List<ShiftAssignmentPlanningEntity> group : shiftsByEmployeeAndDate.values()) {
            if (group.isEmpty()) continue;

            group.sort(Comparator.comparing(a -> a.getOrigin().getStartAt()));
            
            // Create shift assignment (continuous work period)
            Date startAt = group.get(0).getOrigin().getStartAt();
            Date endAt = group.get(group.size() - 1).getOrigin().getEndAt();
            String employeeCode = group.get(0).getAssignedEmployee().getEmployeeCode();
            String storeCode = group.get(0).getOrigin().getStoreCode();

            ShiftAssignment shiftAssignment = new ShiftAssignment();
            shiftAssignment.setStoreCode(storeCode);
            shiftAssignment.setEmployeeCode(employeeCode);
            shiftAssignment.setStartAt(startAt);
            shiftAssignment.setEndAt(endAt);
            shiftAssignment.setCreatedBy("auto");
            shiftAssignments.add(shiftAssignment);
        }

        // Group by employee, date, and register for register assignments
        Map<String, List<ShiftAssignmentPlanningEntity>> registerAssignmentsByEmployeeDateRegister = best.getAssignmentList().stream()
                .filter(a -> a.getAssignedEmployee() != null)
                .collect(Collectors.groupingBy(a -> a.getAssignedEmployee().getEmployeeCode() + "@" + a.getShiftDate().toString() + "@" + a.getRegisterNo()));

        List<RegisterAssignment> mergedRegisterAssignments = new ArrayList<>();
        for (List<ShiftAssignmentPlanningEntity> group : registerAssignmentsByEmployeeDateRegister.values()) {
            if (group.isEmpty()) continue;

            group.sort(Comparator.comparing(a -> a.getOrigin().getStartAt()));

            RegisterAssignment currentMerge = null;
            for (ShiftAssignmentPlanningEntity entity : group) {
                if (currentMerge == null) {
                    currentMerge = entity.getOrigin();
                    currentMerge.setAssignmentId(null); // Ensure new insert
                    currentMerge.setEmployeeCode(entity.getAssignedEmployee().getEmployeeCode());
                } else {
                    // Check if this slot is consecutive
                    if (currentMerge.getEndAt().toInstant().equals(entity.getOrigin().getStartAt().toInstant())) {
                        currentMerge.setEndAt(entity.getOrigin().getEndAt()); // Extend the end time
                    } else {
                        // Not consecutive, save the previous merge and start a new one
                        mergedRegisterAssignments.add(currentMerge);
                        currentMerge = entity.getOrigin();
                        currentMerge.setAssignmentId(null);
                        currentMerge.setEmployeeCode(entity.getAssignedEmployee().getEmployeeCode());
                    }
                }
            }
            if (currentMerge != null) {
                mergedRegisterAssignments.add(currentMerge);
            }
        }

        // -- DB に保存 --
        shiftAssignments.forEach(shiftAssignmentMapper::insert);
        mergedRegisterAssignments.forEach(registerAssignmentMapper::insert);
        
        log.info("Persisted best solution – {} shift assignments and {} register assignments saved (score = {})", 
                shiftAssignments.size(), mergedRegisterAssignments.size(), best.getScore());
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date == null
                ? null
                : date.toInstant()
                      .atZone(ZoneId.systemDefault())
                      .toLocalDateTime();
    }

    /**
     * BestSolutionChangedEvent時に呼ばれるフェーズ更新メソッド
     */
    private void updatePhase(ProblemKey key, ShiftSchedule bestSolution) {
        if (bestSolution == null) return;
        
        var score = bestSolution.getScore();
        if (score != null) {
            String phase;
            
            if (score.initScore() < 0) {
                // Construction Heuristic フェーズ（初期化中）
                phase = "初期解生成中";
            } else {
                // Local Search フェーズ
                phase = "最適化中";
            }
            
            currentPhaseMap.put(key, phase);
            log.debug("Phase update for {}: {} - Score: {}", key, phase, score);
        }
    }

    /**
     * Solver 実行中に例外が発生した場合のハンドラ。
     */
    private void onError(ProblemKey key, Throwable throwable) {
        log.error("Solver failed for problem {}", key, throwable);
        currentPhaseMap.remove(key);
    }

    /* ===================================================================== */
    /* Helper                                                                */
    /* ===================================================================== */

    private static long toProblemId(LocalDate month) {
        return month.getYear() * 100L + month.getMonthValue(); // yyyyMM
    }

    private static LocalDate toMonth(long problemId) {
        int year = (int) (problemId / 100);
        int month = (int) (problemId % 100);
        return LocalDate.of(year, month, 1);
    }
}
