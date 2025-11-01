package io.github.riemr.shift.optimization.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

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
import io.github.riemr.shift.infrastructure.mapper.DepartmentTaskAssignmentMapper;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import io.github.riemr.shift.application.repository.ShiftScheduleRepository;
import io.github.riemr.shift.application.service.AppSettingService;
import io.github.riemr.shift.application.service.TaskPlanService;
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
    private final DepartmentTaskAssignmentMapper departmentTaskAssignmentMapper;
    private final EmployeeRegisterSkillMapper employeeRegisterSkillMapper;
    private final EmployeeMapper employeeMapper;
    private final AppSettingService appSettingService;
    private final TaskPlanService taskPlanService;

    /* === Settings === */
    @Value("${shift.solver.spent-limit:PT5M}") // ISO‑8601 Duration (default 5 minutes)
    private Duration spentLimit;

    /* === Runtime State === */
    private final Map<ProblemKey, Instant> startMap = new ConcurrentHashMap<>();
    private final Map<ProblemKey, SolverJob<ShiftSchedule, ProblemKey>> jobMap = new ConcurrentHashMap<>();
    private final Map<ProblemKey, String> currentPhaseMap = new ConcurrentHashMap<>(); // 現在のフェーズ
    // 開発者向け: スコア推移の時系列
    private final Map<ProblemKey, java.util.List<io.github.riemr.shift.application.dto.ScorePoint>> scoreSeriesMap = new ConcurrentHashMap<>();

    /* ===================================================================== */
    /* Public API                                                            */
    /* ===================================================================== */


    /**
     * 月次シフト計算を非同期で開始。
     * 既に同じ月のジョブが走っている場合はそのステータスを再利用する。
     */
    public SolveTicket startSolveMonth(LocalDate month) {
        return startSolveMonth(month, null, null);
    }

    /**
     * 月次シフト計算を非同期で開始（店舗指定あり）。
     * 既に同じ月のジョブが走っている場合はそのステータスを再利用する。
     */
    public SolveTicket startSolveMonth(LocalDate month, String storeCode) {
        return startSolveMonth(month, storeCode, null);
    }

    /**
     * 月次シフト計算を非同期で開始（店舗・部門指定あり）。
     * 既に同じ月のジョブが走っている場合はそのステータスを再利用する。
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = false)
    public SolveTicket startSolveMonth(LocalDate month, String storeCode, String departmentCode) {
        return startSolveInternal(month, storeCode, departmentCode, "ASSIGNMENT");
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = false)
    public SolveTicket startSolveAttendanceMonth(LocalDate month, String storeCode, String departmentCode) {
        return startSolveInternal(month, storeCode, departmentCode, "ATTENDANCE");
    }

    private SolveTicket startSolveInternal(LocalDate month, String storeCode, String departmentCode, String stage) {
        long problemId = toProblemId(month);
        ProblemKey key = new ProblemKey(java.time.YearMonth.from(month), storeCode, departmentCode, month, stage);

        // 事前準備処理を最適化サービス内で同期実行
        if (storeCode != null && !storeCode.isBlank()) {
            try {
                LocalDate cycleStart = month;
                LocalDate cycleEnd = month.plusMonths(1);
                
                log.info("Executing task plan materialization for store: {}, dept: {}", storeCode, departmentCode);
        System.out.println("DEBUG: ShiftScheduleService executing task plan materialization for store: " + storeCode + ", dept: " + departmentCode);
                
                // taskPlanServiceを使用して作業計画を物質化
                taskPlanService.applyReplacing(storeCode, cycleStart, cycleEnd.minusDays(1), "optimization_prep");
                
                if (departmentCode != null && !departmentCode.isBlank()) {
                    System.out.println("DEBUG: Processing specific department: " + departmentCode);
                    // 部門タスク割当（従業員未割当の枠）も物質化しておく
                    try {
                        int createdDeptAssign = taskPlanService.materializeDepartmentAssignments(storeCode, departmentCode, cycleStart, cycleEnd, "optimization_prep");
                        log.info("✅ Materialized {} department task assignments for dept: {}", createdDeptAssign, departmentCode);
                        System.out.println("DEBUG: Materialized " + createdDeptAssign + " department task assignments for dept: " + departmentCode);
                    } catch (Exception ex) {
                        log.warn("Department task assignment materialization failed for dept {}: {}", departmentCode, ex.getMessage());
                    }
                    int createdWorkDemands = taskPlanService.materializeWorkDemands(storeCode, departmentCode, cycleStart, cycleEnd);
                    log.info("✅ Created {} work demand intervals for dept: {}", createdWorkDemands, departmentCode);
                    System.out.println("DEBUG: Created " + createdWorkDemands + " work demand intervals for dept: " + departmentCode);
                } else {
                    System.out.println("DEBUG: Processing ALL departments (departmentCode is null or blank)");
                    int createdWorkDemands = taskPlanService.materializeWorkDemandsForAllDepartments(storeCode, cycleStart, cycleEnd);
                    log.info("✅ Created {} work demand intervals for all departments", createdWorkDemands);
                    System.out.println("DEBUG: Created " + createdWorkDemands + " work demand intervals for all departments");
                }
            } catch (Exception e) {
                log.error("❌ Task plan materialization failed", e);
                // エラーが発生してもOptaPlanner処理は続行
            }
        }
        
        log.info("Starting optimization for month={}, store={}, dept={}, stage={} (task plan preparation completed)", month, storeCode, departmentCode, stage);

        // 既存ジョブならチケット再発行（startMapが未設定でもNPEにしない）
        if (jobMap.containsKey(key)) {
            Instant started = startMap.get(key);
            if (started == null) {
                started = Instant.now();
                startMap.put(key, started);
            }
            return new SolveTicket(problemId,
                    started.toEpochMilli(),
                    started.plus(spentLimit).toEpochMilli());
        }

        // 進捗メタ情報（レース防止のため先に開始時刻を記録）
        Instant start = Instant.now();
        startMap.put(key, start);

        // Solver 起動 (listen)
        SolverJob<ShiftSchedule, ProblemKey> job = solverManager.solveAndListen(
                key,
                this::loadProblem,
                bestSolution -> {
                    // フェーズ・スコアの更新
                    updatePhase(key, bestSolution);
                    recordScorePoint(key, bestSolution);
                    persistResult(bestSolution, key);
                },
                this::onError);
        jobMap.put(key, job);

        return new SolveTicket(problemId,
                start.toEpochMilli(),
                start.plus(spentLimit).toEpochMilli());
    }

    /** 進捗バー用ステータス */
    public SolveStatusDto getStatus(Long problemId, String storeCode, String departmentCode) {
        // 後方互換: ステージ無指定は最初に見つかったものを返す
        for (ProblemKey key : jobMap.keySet()) {
            if (Objects.equals(key.getStoreCode(), storeCode) &&
                Objects.equals(key.getDepartmentCode(), departmentCode) &&
                toProblemId(key.getCycleStart()) == problemId) {
                return internalStatus(key);
            }
        }
        return new SolveStatusDto("UNKNOWN", 0, 0, "未開始");
    }

    public SolveStatusDto getStatus(Long problemId, String storeCode, String departmentCode, String stage) {
        for (ProblemKey key : jobMap.keySet()) {
            if (Objects.equals(key.getStoreCode(), storeCode) &&
                Objects.equals(key.getDepartmentCode(), departmentCode) &&
                toProblemId(key.getCycleStart()) == problemId &&
                Objects.equals(key.getStage(), stage)) {
                return internalStatus(key);
            }
        }
        return new SolveStatusDto("UNKNOWN", 0, 0, "未開始");
    }

    private SolveStatusDto internalStatus(ProblemKey key) {
        SolverStatus status = solverManager.getSolverStatus(key);
        Instant started = startMap.get(key);
        if (started == null) {
            // ジョブ開始時刻が消えている（完了後の参照やレース）場合でもNPEにせず安全な既定値で扱う
            started = Instant.now();
        }
        long start = started.toEpochMilli();
        long finish = started.plus(spentLimit).toEpochMilli();
        int pct = (int) Math.min(100, Math.max(0,
                Math.round((System.currentTimeMillis() - start) * 100.0 / Math.max(1, finish - start))))
                ;
        String currentPhase = currentPhaseMap.getOrDefault(key, "初期化中");
        if (status == SolverStatus.NOT_SOLVING) {
            startMap.remove(key);
            currentPhaseMap.remove(key);
        }

        return new SolveStatusDto(status.name(), pct, finish, currentPhase);
    }

    private void recordScorePoint(ProblemKey key, ShiftSchedule best) {
        if (best == null || best.getScore() == null) return;
        var s = best.getScore();
        int init = s.initScore();
        int hard = s.hardScore();
        int soft = s.softScore();
        long now = System.currentTimeMillis();
        scoreSeriesMap.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new io.github.riemr.shift.application.dto.ScorePoint(now, init, hard, soft));
        // keep last 1000 points to bound memory
        var list = scoreSeriesMap.get(key);
        if (list.size() > 1000) {
            list.subList(0, list.size() - 1000).clear();
        }
    }

    // 開発者向け: スコア推移を取得（deptのnull/空白を同一視）
    public java.util.List<io.github.riemr.shift.application.dto.ScorePoint> getScoreSeries(long problemId, String storeCode, String departmentCode) {
        String deptNorm = (departmentCode == null || departmentCode.isBlank()) ? null : departmentCode;
        for (ProblemKey key : scoreSeriesMap.keySet()) {
            boolean storeMatch = java.util.Objects.equals(key.getStoreCode(), storeCode);
            boolean probMatch = toProblemId(key.getCycleStart()) == problemId;
            String keyDept = key.getDepartmentCode();
            boolean deptMatch = (deptNorm == null ? (keyDept == null || keyDept.isBlank()) : java.util.Objects.equals(keyDept, deptNorm));
            if (storeMatch && probMatch && deptMatch) {
                return scoreSeriesMap.getOrDefault(key, java.util.List.of());
            }
        }
        // 部門が見つからない場合、store+problemId だけで近いものを返す（最後の手段）
        for (ProblemKey key : scoreSeriesMap.keySet()) {
            if (java.util.Objects.equals(key.getStoreCode(), storeCode) && toProblemId(key.getCycleStart()) == problemId) {
                return scoreSeriesMap.getOrDefault(key, java.util.List.of());
            }
        }
        return java.util.List.of();
    }

    /** 計算終了後の最終解をフロント用 DTO に変換して返す */
    public List<ShiftAssignmentView> fetchResult(Long problemId, String storeCode, String departmentCode) {
        // 後方互換: 最初に見つかったステージの結果
        for (ProblemKey k : jobMap.keySet()) {
            if (Objects.equals(k.getStoreCode(), storeCode) &&
                Objects.equals(k.getDepartmentCode(), departmentCode) &&
                toProblemId(k.getCycleStart()) == problemId) {
                return internalFetchResult(k);
            }
        }
        return List.of();
    }

    public List<ShiftAssignmentView> fetchResult(Long problemId, String storeCode, String departmentCode, String stage) {
        for (ProblemKey k : jobMap.keySet()) {
            if (Objects.equals(k.getStoreCode(), storeCode) &&
                Objects.equals(k.getDepartmentCode(), departmentCode) &&
                toProblemId(k.getCycleStart()) == problemId &&
                Objects.equals(k.getStage(), stage)) {
                return internalFetchResult(k);
            }
        }
        return List.of();
    }

    private List<ShiftAssignmentView> internalFetchResult(ProblemKey key) {
        SolverJob<ShiftSchedule, ProblemKey> job = jobMap.get(key);
        if (job == null) return List.of();
        try {
            ShiftSchedule solved = job.getFinalBestSolution();
            return solved.getAssignmentList().stream()
                    .map(a -> new ShiftAssignmentView(
                            a.getOrigin().getStartAt().toString(),
                            a.getOrigin().getEndAt().toString(),
                            a.getOrigin().getRegisterNo(),
                            a.getDepartmentCode(),
                            a.getWorkKind() != null ? a.getWorkKind().name() : null,
                            a.getTaskCode(),
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
    public List<ShiftAssignmentMonthlyView> fetchAssignmentsByMonth(LocalDate anyDayInMonth, String storeCode, String departmentCode) {
        LocalDate from = computeCycleStart(anyDayInMonth);
        LocalDate to   = from.plusMonths(1);  // 半開区間

        // 事前に従業員名を一括取得
        Map<String, String> nameMap = (storeCode != null ? employeeMapper.selectByStoreCode(storeCode) : employeeMapper.selectAll())
                .stream().collect(Collectors.toMap(e -> e.getEmployeeCode(), e -> e.getEmployeeName(), (a,b)->a));

        if (departmentCode != null && !departmentCode.isBlank() && !"520".equalsIgnoreCase(departmentCode)) {
            // Department task assignments monthly
            var taskList = departmentTaskAssignmentMapper.selectByMonth(from, to, storeCode, departmentCode);
            return taskList.stream()
                    .map(t -> new ShiftAssignmentMonthlyView(
                            toLocalDateTime(t.getStartAt()),
                            toLocalDateTime(t.getEndAt()),
                            null,
                            t.getEmployeeCode(),
                            Optional.ofNullable(t.getEmployeeCode()).map(code -> nameMap.getOrDefault(code, "")).orElse("")
                    ))
                    .toList();
        } else {
            // Register assignments monthly
            List<RegisterAssignment> assignments = registerAssignmentMapper.selectByMonth(from, to)
                    .stream()
                    .filter(a -> (storeCode == null || storeCode.equals(a.getStoreCode())))
                    .toList();
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
    }

    // Backward-compatible overloads (internally delegate with departmentCode = null)
    public List<ShiftAssignmentMonthlyView> fetchAssignmentsByMonth(LocalDate anyDayInMonth, String storeCode) {
        return fetchAssignmentsByMonth(anyDayInMonth, storeCode, null);
    }

    /** 月別出勤時間取得 - シフトアサインメント表示 */
    public List<ShiftAssignmentMonthlyView> fetchShiftsByMonth(LocalDate anyDayInMonth, String storeCode, String departmentCode) {
        LocalDate from = computeCycleStart(anyDayInMonth);
        LocalDate to   = from.plusMonths(1);  // 半開区間

        List<ShiftAssignment> shifts = shiftAssignmentMapper.selectByMonth(from, to)
                .stream()
                .filter(s -> (storeCode == null || storeCode.equals(s.getStoreCode())))
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

    public List<ShiftAssignmentMonthlyView> fetchShiftsByMonth(LocalDate anyDayInMonth, String storeCode) {
        return fetchShiftsByMonth(anyDayInMonth, storeCode, null);
    }

    /**
     * 出勤（shift_assignment）を月次単位で削除する（店舗単位）。
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = false)
    public int clearAttendance(LocalDate anyDayInMonth, String storeCode) {
        LocalDate from = computeCycleStart(anyDayInMonth);
        LocalDate to   = from.plusMonths(1);
        if (storeCode == null || storeCode.isBlank()) return 0;
        return shiftAssignmentMapper.deleteByMonthAndStore(from, to, storeCode);
    }

    /**
     * 作業割当（register_assignment, department_task_assignment）を月次単位で削除する。
     * 部門が指定されている場合はその部門のタスクのみ削除。
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = false)
    public int clearWorkAssignments(LocalDate anyDayInMonth, String storeCode, String departmentCode) {
        LocalDate from = computeCycleStart(anyDayInMonth);
        LocalDate to   = from.plusMonths(1);
        if (storeCode == null || storeCode.isBlank()) return 0;
        int total = 0;
        total += registerAssignmentMapper.deleteByMonthAndStore(from, to, storeCode);
        if (departmentCode != null && !departmentCode.isBlank()) {
            total += departmentTaskAssignmentMapper.deleteByMonthStoreAndDepartment(from, to, storeCode, departmentCode);
        } else {
            try {
                java.lang.reflect.Method m = departmentTaskAssignmentMapper.getClass().getMethod("deleteByMonthAndStore", java.time.LocalDate.class, java.time.LocalDate.class, String.class);
                Object r = m.invoke(departmentTaskAssignmentMapper, from, to, storeCode);
                if (r instanceof Integer i) total += i;
            } catch (Exception ignore) { /* メソッドが無ければスキップ */ }
        }
        return total;
    }

    public List<ShiftAssignmentView> fetchAssignmentsByDate(LocalDate date, String storeCode, String departmentCode) {
        Map<String, String> nameMap = (storeCode != null ? employeeMapper.selectByStoreCode(storeCode) : employeeMapper.selectAll())
                .stream().collect(Collectors.toMap(e -> e.getEmployeeCode(), e -> e.getEmployeeName(), (a,b)->a));

        if (departmentCode != null && !departmentCode.isBlank() && !"520".equalsIgnoreCase(departmentCode)) {
            // Department task assignments
            var from = date;
            var to = date.plusDays(1);
            var taskMapper = this.departmentTaskAssignmentMapper; // injected
            var tasks = taskMapper.selectByMonth(from, to, storeCode, departmentCode);
            return tasks.stream().map(t -> new ShiftAssignmentView(
                    Optional.ofNullable(t.getStartAt()).map(d -> d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString()).orElse(""),
                    Optional.ofNullable(t.getEndAt()).map(d -> d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString()).orElse(""),
                    null,
                    departmentCode,
                    "DEPARTMENT_TASK",
                    t.getTaskCode(),
                    Optional.ofNullable(t.getEmployeeCode()).orElse(""),
                    Optional.ofNullable(t.getEmployeeCode()).map(code -> nameMap.getOrDefault(code, "")).orElse("")
            )).toList();
        } else {
            // Register assignments
            List<RegisterAssignment> assignments = registerAssignmentMapper.selectByDate(date, date.plusDays(1))
                    .stream()
                    .filter(a -> (storeCode == null || storeCode.equals(a.getStoreCode())))
                    .toList();
            return assignments.stream()
                    .map(a -> new ShiftAssignmentView(
                            Optional.ofNullable(a.getStartAt())
                                    .map(d -> d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                                    .orElse(""),
                            Optional.ofNullable(a.getEndAt())
                                    .map(d -> d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                                    .orElse(""),
                            a.getRegisterNo(),
                            departmentCode,
                            "REGISTER_OP",
                            null,
                            Optional.ofNullable(a.getEmployeeCode()).orElse(""),
                            Optional.ofNullable(a.getEmployeeCode()).map(code -> nameMap.getOrDefault(code, "")).orElse("")
                    ))
                    .toList();
        }
    }

    public List<ShiftAssignmentView> fetchAssignmentsByDate(LocalDate date, String storeCode) {
        return fetchAssignmentsByDate(date, storeCode, null);
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
            
            // 既存の割り当てを削除（店舗単位に限定）
            String storeCode = request.storeCode();
            if (storeCode != null && !storeCode.isBlank()) {
                registerAssignmentMapper.deleteByEmployeeCodeStoreAndTimeRange(employeeCode, storeCode, startAt, endAt);
            } else {
                registerAssignmentMapper.deleteByEmployeeCodeAndTimeRange(employeeCode, startAt, endAt);
            }
            
            // 新しい割り当てを作成（currentRegisterが空でない場合）
            if (currentRegister != null && !currentRegister.trim().isEmpty()) {
                RegisterAssignment assignment = new RegisterAssignment();
                assignment.setStoreCode(storeCode);
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
        // ProblemKeyからサイクル開始日を取得、なければ従来の方法
        LocalDate cycleStart = key.getCycleStart() != null 
            ? key.getCycleStart() 
            : LocalDate.of(key.getMonth().getYear(), key.getMonth().getMonthValue(), 1);
        ShiftSchedule unsolved = repository.fetchShiftSchedule(cycleStart, key.getStoreCode(), key.getDepartmentCode());
        unsolved.setEmployeeRegisterSkillList(employeeRegisterSkillMapper.selectByExample(null));
        // Repository 側で必要なフィールドをセット済みだが、問題 ID だけはここで上書きしておく
        unsolved.setProblemId(toProblemId(cycleStart));
        if (unsolved.getAssignmentList() == null) {
            unsolved.setAssignmentList(new ArrayList<>());
        }
        
        // ステージをエンティティへ伝搬
        if (unsolved.getAssignmentList() != null) {
            for (var a : unsolved.getAssignmentList()) {
                a.setStage(key.getStage());
            }
        }

        // ステージごとの可用従業員候補を事前計算（ピン留め相当のフィルタリング）
        try {
            prepareCandidateEmployees(unsolved, key.getStage(), cycleStart);
        } catch (Exception ex) {
            log.warn("Failed to prepare candidate employees: {}", ex.getMessage());
        }

        // 実行可能性チェック：全員希望休の日があるかチェック
        validateProblemFeasibility(unsolved);

        // 追加診断: スロットごとの候補従業員数を集計し、極端にゼロが多い場合に警告
        try {
            diagnoseFeasibility(unsolved);
        } catch (Exception diagEx) {
            log.debug("Feasibility diagnostics skipped: {}", diagEx.getMessage());
        }
        
        log.info("Loaded unsolved problem for {} store {} ({} assignments)", cycleStart, unsolved.getStoreCode(), unsolved.getAssignmentList().size());
        return unsolved;
    }

    // ATTENDANCE: 有休/希望休/曜日OFFは候補から除外（休みをピン留め）
    // ASSIGNMENT: 当該スロットで出勤中の従業員のみを候補化
    private void prepareCandidateEmployees(ShiftSchedule schedule, String stage, LocalDate cycleStart) {
        if (schedule.getAssignmentList() == null || schedule.getEmployeeList() == null) return;

        final var employees = schedule.getEmployeeList();
        final var requests = java.util.Optional.ofNullable(schedule.getEmployeeRequestList()).orElse(java.util.List.of());
        final var weekly = java.util.Optional.ofNullable(schedule.getEmployeeWeeklyPreferenceList()).orElse(java.util.List.of());

        // インデックス化
        java.util.Map<String, java.util.Set<LocalDate>> offDatesByEmp = new java.util.HashMap<>();
        for (var r : requests) {
            if (r.getRequestKind() == null) continue;
            String kind = r.getRequestKind().toLowerCase();
            if ("off".equals(kind) || "paid_leave".equals(kind)) {
                LocalDate d = r.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                offDatesByEmp.computeIfAbsent(r.getEmployeeCode(), k -> new java.util.HashSet<>()).add(d);
            }
        }
        java.util.Map<String, java.util.Set<Integer>> weeklyOffByEmp = new java.util.HashMap<>();
        for (var p : weekly) {
            if ("OFF".equalsIgnoreCase(p.getWorkStyle())) {
                weeklyOffByEmp.computeIfAbsent(p.getEmployeeCode(), k -> new java.util.HashSet<>())
                        .add(p.getDayOfWeek().intValue());
            }
        }

        // ASSIGNMENT用: 出勤ロスター（DBのshift_assignment）をロード
        java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment> attendance = java.util.List.of();
        if (stage != null && stage.equals("ASSIGNMENT")) {
            LocalDate cycleEnd = cycleStart.plusMonths(1);
            var list = shiftAssignmentMapper.selectByMonth(cycleStart, cycleEnd);
            if (schedule.getStoreCode() != null) {
                list = list.stream().filter(sa -> schedule.getStoreCode().equals(sa.getStoreCode())).toList();
            }
            attendance = list;
        }

        for (var a : schedule.getAssignmentList()) {
            LocalDate date = a.getShiftDate();
            java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.Employee> cands;
            if ("ATTENDANCE".equals(stage)) {
                cands = employees.stream().filter(e -> {
                    var offSet = offDatesByEmp.getOrDefault(e.getEmployeeCode(), java.util.Set.of());
                    if (offSet.contains(date)) return false; // 有休/希望休
                    var offDow = weeklyOffByEmp.getOrDefault(e.getEmployeeCode(), java.util.Set.of());
                    return !offDow.contains(date.getDayOfWeek().getValue()); // 曜日OFF
                }).toList();
            } else if ("ASSIGNMENT".equals(stage)) {
                // 当該スロット内に出勤が重なる従業員のみ
                var start = a.getStartAt().toInstant();
                var end = a.getEndAt().toInstant();
                java.util.Set<String> onDuty = new java.util.HashSet<>();
                for (var sa : attendance) {
                    if (sa.getStartAt() == null || sa.getEndAt() == null) continue;
                    var s = sa.getStartAt().toInstant();
                    var e = sa.getEndAt().toInstant();
                    boolean overlap = s.isBefore(end) && e.isAfter(start);
                    if (overlap) {
                        onDuty.add(sa.getEmployeeCode());
                    }
                }
                cands = employees.stream().filter(e -> onDuty.contains(e.getEmployeeCode())).toList();
            } else {
                cands = employees; // デフォルト（フェールセーフ）
            }
            a.setCandidateEmployees(cands);
        }

        // ATTENDANCE: 出勤固定（MANDATORY）の人は、その日に少なくとも1スロットは必ず候補を単一化して“ピン”に近い状態にする
        if ("ATTENDANCE".equals(stage)) {
            // 週MANDATORYをインデックス化（OFFや有休が優先されるため、該当日は除外）
            java.util.Map<LocalDate, java.util.List<String>> mandatoryByDate = new java.util.HashMap<>();
            for (var p : weekly) {
                if (!"MANDATORY".equalsIgnoreCase(p.getWorkStyle())) continue;
                String emp = p.getEmployeeCode();
                // 対象月全日に対して、該当DOWを付与（簡便実装）
                LocalDate d = cycleStart;
                LocalDate end = cycleStart.plusMonths(1);
                while (d.isBefore(end)) {
                    if (d.getDayOfWeek().getValue() == p.getDayOfWeek().intValue()) {
                        // OFF/有休は優先：この日はMANDATORYから除外
                        var offSet = offDatesByEmp.getOrDefault(emp, java.util.Set.of());
                        var offDow = weeklyOffByEmp.getOrDefault(emp, java.util.Set.of());
                        if (!offSet.contains(d) && !offDow.contains(d.getDayOfWeek().getValue())) {
                            mandatoryByDate.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(emp);
                        }
                    }
                    d = d.plusDays(1);
                }
            }

            // 日付ごとに未予約スロットへ順に単一候補化（過度な拘束を避けるため各MANDATORYあたり1スロット）
            java.util.Map<LocalDate, java.util.List<ShiftAssignmentPlanningEntity>> byDate = new java.util.HashMap<>();
            for (var a : schedule.getAssignmentList()) {
                byDate.computeIfAbsent(a.getShiftDate(), k -> new java.util.ArrayList<>()).add(a);
            }
            for (var entry : mandatoryByDate.entrySet()) {
                LocalDate date = entry.getKey();
                var emps = entry.getValue();
                var slots = byDate.getOrDefault(date, java.util.List.of());
                int idx = 0;
                for (String emp : emps) {
                    // 空きを探す（既に単一化されていないスロット）
                    ShiftAssignmentPlanningEntity target = null;
                    for (int i = idx; i < slots.size(); i++) {
                        var a = slots.get(i);
                        var c = a.getAvailableEmployees();
                        if (c != null && c.size() > 1) { target = a; idx = i + 1; break; }
                    }
                    if (target == null && !slots.isEmpty()) {
                        // すべて単一化済みなら最初を上書き（最小限の影響）
                        target = slots.get(0);
                    }
                    if (target != null) {
                        // 指定従業員が候補にいない場合はスキップ（例: スキルや別理由で不可）
                        var exists = target.getAvailableEmployees().stream().anyMatch(e -> emp.equals(e.getEmployeeCode()));
                        if (exists) {
                            var only = employees.stream().filter(e -> emp.equals(e.getEmployeeCode())).toList();
                            target.setCandidateEmployees(only);
                        }
                    }
                }
            }
        }
    }

    /**
     * ハード制約の単体（スロット単位）チェックで、各アサイン可能スロットに候補従業員が存在するかを診断する。
     * 相互依存（ダブルブッキング、日次上限等）は無視し、以下をチェック:
     *  - 希望休（off）
     *  - 曜日OFF/基本時間外
     *  - レジ技能 0/1 禁止（REGISTER_OP 時）
     */
    private void diagnoseFeasibility(ShiftSchedule s) {
        if (s.getAssignmentList() == null || s.getEmployeeList() == null) return;

        // インデックス化
        Map<String, List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference>> prefByEmp =
                Optional.ofNullable(s.getEmployeeWeeklyPreferenceList()).orElse(List.of()).stream()
                        .collect(Collectors.groupingBy(io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference::getEmployeeCode));

        Map<String, Map<Integer, Short>> skillByEmpRegister = new HashMap<>();
        for (var sk : Optional.ofNullable(s.getEmployeeRegisterSkillList()).orElse(List.of())) {
            skillByEmpRegister
                    .computeIfAbsent(sk.getEmployeeCode(), k -> new HashMap<>())
                    .put(sk.getRegisterNo(), sk.getSkillLevel());
        }

        Map<String, Set<LocalDate>> dayOffByEmp = new HashMap<>();
        for (var req : Optional.ofNullable(s.getEmployeeRequestList()).orElse(List.of())) {
            if ("off".equalsIgnoreCase(req.getRequestKind())) {
                String emp = req.getEmployeeCode();
                LocalDate d = req.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                dayOffByEmp.computeIfAbsent(emp, k -> new java.util.HashSet<>()).add(d);
            }
        }

        int total = 0;
        int noCandidate = 0;
        int registerSlots = 0;

        for (var a : s.getAssignmentList()) {
            total++;
            LocalDate date = a.getShiftDate();
            var start = a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
            var end = a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime();

            int candidates = 0;
            for (var e : s.getEmployeeList()) {
                String code = e.getEmployeeCode();

                // 希望休
                if (dayOffByEmp.getOrDefault(code, Set.of()).contains(date)) continue;

                // 曜日OFF/基本時間外
                var prefs = prefByEmp.getOrDefault(code, List.of());
                boolean offDay = false;
                boolean outsideBase = false;
                for (var p : prefs) {
                    if (p.getDayOfWeek() == null) continue;
                    if (p.getDayOfWeek().intValue() != date.getDayOfWeek().getValue()) continue;
                    if ("OFF".equalsIgnoreCase(p.getWorkStyle())) { offDay = true; break; }
                    if (p.getBaseStartTime() != null && p.getBaseEndTime() != null) {
                        var bs = p.getBaseStartTime().toLocalTime();
                        var be = p.getBaseEndTime().toLocalTime();
                        if (start.isBefore(bs) || end.isAfter(be)) {
                            outsideBase = true; // 厳密
                        }
                    }
                }
                if (offDay || outsideBase) continue;

                // レジ技能（REGISTER_OP のみチェック）
                if (a.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.REGISTER_OP) {
                    registerSlots++;
                    Integer reg = a.getRegisterNo();
                    if (reg != null) {
                        Short lv = skillByEmpRegister.getOrDefault(code, Map.of()).get(reg);
                        if (lv != null && (lv == 0 || lv == 1)) {
                            continue; // 禁止
                        }
                    }
                }

                candidates++;
                if (candidates >= 1) break; // 1人いれば十分
            }
            if (candidates == 0) noCandidate++;
        }

        if (noCandidate > 0) {
            double pct = (total == 0) ? 0.0 : (noCandidate * 100.0 / total);
            log.warn("⚠️ FEASIBILITY DIAG: {} / {} slots have zero candidates ({}%). registerSlots={}",
                    noCandidate, total, String.format("%.1f", pct), registerSlots);
            if (pct >= 50.0) {
                log.warn("Likely cause: OFF日/基本時間外/スキル0/1が厳しすぎる、または従業員が不足しています。設定とデータを確認してください。");
            }
        } else {
            log.info("Feasibility DIAG: All slots have at least one candidate.");
        }
    }
    
    /**
     * 問題の実行可能性をチェックし、警告を出力
     */
    private void validateProblemFeasibility(ShiftSchedule schedule) {
        if (schedule.getEmployeeRequestList() == null || schedule.getEmployeeList() == null) {
            return;
        }
        
        // 日付ごとの希望休者数をカウント
        Map<LocalDate, Long> dayOffCounts = schedule.getEmployeeRequestList().stream()
            .filter(req -> "off".equalsIgnoreCase(req.getRequestKind()))
            .collect(Collectors.groupingBy(
                req -> req.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate(),
                Collectors.counting()
            ));
        
        long totalEmployees = schedule.getEmployeeList().size();
        
        dayOffCounts.forEach((date, count) -> {
            if (count >= totalEmployees) {
                log.warn("⚠️ FEASIBILITY WARNING: All {} employees have requested day off on {}. " +
                        "Hard constraints will be violated!", count, date);
            } else if (count > totalEmployees * 0.8) {
                log.warn("⚠️ FEASIBILITY WARNING: {}% of employees ({}/{}) have requested day off on {}. " +
                        "Optimal solution may be difficult to find.", 
                        Math.round(count * 100.0 / totalEmployees), count, totalEmployees, date);
            }
        });
    }
    
    /**
     * 制約違反の詳細分析と改善提案を出力
     */
    private void analyzeConstraintViolations(ShiftSchedule schedule) {
        if (schedule.getEmployeeRequestList() == null || schedule.getAssignmentList() == null) {
            return;
        }
        
        // 希望休違反の分析
        Map<LocalDate, List<String>> dayOffViolations = new HashMap<>();
        
        // 割り当てられた従業員の希望休チェック
        schedule.getAssignmentList().stream()
            .filter(assignment -> assignment.getAssignedEmployee() != null)
            .forEach(assignment -> {
                String employeeCode = assignment.getAssignedEmployee().getEmployeeCode();
                LocalDate shiftDate = assignment.getShiftDate();
                
                // この従業員がこの日に希望休を出していないかチェック
                boolean hasRequestedOff = schedule.getEmployeeRequestList().stream()
                    .anyMatch(req -> 
                        employeeCode.equals(req.getEmployeeCode()) &&
                        "off".equalsIgnoreCase(req.getRequestKind()) &&
                        shiftDate.equals(req.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate())
                    );
                
                if (hasRequestedOff) {
                    dayOffViolations.computeIfAbsent(shiftDate, k -> new ArrayList<>()).add(employeeCode);
                }
            });
        
        // 希望休違反の詳細報告
        if (!dayOffViolations.isEmpty()) {
            log.error("🔴 REQUESTED DAY OFF VIOLATIONS:");
            dayOffViolations.forEach((date, employees) -> {
                log.error("  📅 {}: {} employees assigned despite requesting day off: {}", 
                         date, employees.size(), String.join(", ", employees));
            });
            
            // 改善提案
            log.error("💡 IMPROVEMENT SUGGESTIONS:");
            log.error("  1. Remove day-off requests for the dates above");
            log.error("  2. Or ensure minimum staffing by removing some day-off requests");
            log.error("  3. Consider closing the store on days when all employees request time off");
        }
        
        // スキルレベル違反の分析
        analyzeSkillViolations(schedule);
        
        // その他のハード制約違反の可能性
        log.error("🔍 OTHER POSSIBLE CONSTRAINT VIOLATIONS:");
        log.error("  - Check employee skill levels for all register types");
        log.error("  - Verify maximum work hours per day settings");
        log.error("  - Review consecutive work day limits");
        log.error("  - Ensure lunch break requirements are feasible");
    }
    
    /**
     * UI用の制約違反分析 - フロントエンド向けのメッセージを生成
     */
    private List<String> analyzeConstraintViolationsForUI(ShiftSchedule schedule) {
        List<String> messages = new ArrayList<>();
        
        if (schedule.getEmployeeRequestList() == null || schedule.getAssignmentList() == null) {
            messages.add("制約違反の詳細分析に必要なデータが不足しています。");
            return messages;
        }
        
        // 希望休違反の分析
        Map<LocalDate, List<String>> dayOffViolations = new HashMap<>();
        
        // 割り当てられた従業員の希望休チェック
        schedule.getAssignmentList().stream()
            .filter(assignment -> assignment.getAssignedEmployee() != null)
            .forEach(assignment -> {
                String employeeCode = assignment.getAssignedEmployee().getEmployeeCode();
                LocalDate shiftDate = assignment.getShiftDate();
                
                // この従業員がこの日に希望休を出していないかチェック
                boolean hasRequestedOff = schedule.getEmployeeRequestList().stream()
                    .anyMatch(req -> 
                        employeeCode.equals(req.getEmployeeCode()) &&
                        "off".equalsIgnoreCase(req.getRequestKind()) &&
                        shiftDate.equals(req.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate())
                    );
                
                if (hasRequestedOff) {
                    dayOffViolations.computeIfAbsent(shiftDate, k -> new ArrayList<>()).add(employeeCode);
                }
            });
        
        // 希望休違反のメッセージ生成
        if (!dayOffViolations.isEmpty()) {
            messages.add("⚠️ 希望休違反が検出されました");
            dayOffViolations.forEach((date, employees) -> {
                messages.add(String.format("📅 %s: %d名が希望休にも関わらず割り当てられています", 
                           date, employees.size()));
            });
            
            messages.add("💡 改善方法:");
            messages.add("• 最低限の人員確保のため一部の希望休を調整する");
        }
        
        // スキルレベル違反の分析
        List<String> skillViolations = analyzeSkillViolationsForUI(schedule);
        messages.addAll(skillViolations);
        
        if (messages.isEmpty()) {
            messages.add("制約違反の詳細を分析中です...");
        }
        
        return messages;
    }
    
    /**
     * スキルレベル違反の分析
     */
    private void analyzeSkillViolations(ShiftSchedule schedule) {
        if (schedule.getEmployeeRegisterSkillList() == null || schedule.getAssignmentList() == null) {
            return;
        }
        
        List<String> skillViolations = new ArrayList<>();
        
        schedule.getAssignmentList().stream()
            .filter(assignment -> assignment.getAssignedEmployee() != null)
            .forEach(assignment -> {
                String employeeCode = assignment.getAssignedEmployee().getEmployeeCode();
                Integer registerNo = assignment.getRegisterNo();
                
                // このレジに対するスキルレベルを確認
                Optional<Short> skillLevel = schedule.getEmployeeRegisterSkillList().stream()
                    .filter(skill -> 
                        employeeCode.equals(skill.getEmployeeCode()) && 
                        registerNo.equals(skill.getRegisterNo()))
                    .map(skill -> skill.getSkillLevel())
                    .findFirst();
                
                if (skillLevel.isPresent() && (skillLevel.get() == 0 || skillLevel.get() == 1)) {
                    skillViolations.add(String.format("Employee %s assigned to Register %d (skill level: %d)", 
                                                     employeeCode, registerNo, skillLevel.get()));
                }
            });
        
        if (!skillViolations.isEmpty()) {
            log.error("🔴 SKILL LEVEL VIOLATIONS:");
            skillViolations.forEach(violation -> log.error("  ⚠️ {}", violation));
            log.error("💡 SKILL IMPROVEMENT SUGGESTIONS:");
            log.error("  1. Increase skill levels (0→2+, 1→2+) for the employees above");
            log.error("  2. Assign skilled employees to cover these registers");
            log.error("  3. Provide training to improve employee capabilities");
        }
    }
    
    /**
     * UI用のスキルレベル違反分析
     */
    private List<String> analyzeSkillViolationsForUI(ShiftSchedule schedule) {
        List<String> messages = new ArrayList<>();
        
        if (schedule.getEmployeeRegisterSkillList() == null || schedule.getAssignmentList() == null) {
            return messages;
        }
        
        List<String> skillViolations = new ArrayList<>();
        
        schedule.getAssignmentList().stream()
            .filter(assignment -> assignment.getAssignedEmployee() != null)
            .forEach(assignment -> {
                String employeeCode = assignment.getAssignedEmployee().getEmployeeCode();
                Integer registerNo = assignment.getRegisterNo();
                
                // このレジに対するスキルレベルを確認
                Optional<Short> skillLevel = schedule.getEmployeeRegisterSkillList().stream()
                    .filter(skill -> 
                        employeeCode.equals(skill.getEmployeeCode()) && 
                        registerNo.equals(skill.getRegisterNo()))
                    .map(skill -> skill.getSkillLevel())
                    .findFirst();
                
                if (skillLevel.isPresent() && (skillLevel.get() == 0 || skillLevel.get() == 1)) {
                    String levelText = skillLevel.get() == 0 ? "自動割当不可" : "割り当て不可";
                    skillViolations.add(String.format("従業員 %s がレジ %d に割り当て (%s)", 
                                                     employeeCode, registerNo, levelText));
                }
            });
        
        if (!skillViolations.isEmpty()) {
            messages.add("⚠️ スキルレベル違反が検出されました");
            skillViolations.forEach(violation -> messages.add("• " + violation));
            messages.add("💡 改善方法:");
            messages.add("• 該当従業員のスキルレベルを2以上に変更する");
            messages.add("• 適切なスキルを持つ従業員をこのレジに割り当てる");
            messages.add("• 従業員の研修を実施してスキル向上を図る");
        }
        
        return messages;
    }

    /**
     * 新しいベスト解が到着する度に呼び出され、DB に永続化する。
     * shift_assignmentテーブルには出勤時間を、register_assignmentテーブルにはレジアサイン時間を保存する。
     * ハード制約違反がある場合は保存を阻止し、アラートを出力する。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    private void persistResult(ShiftSchedule best, ProblemKey key) {
        // Construction Heuristic中（未割当が残っている）なら保存をスキップ
        if (best.getScore() != null && best.getScore().initScore() < 0) {
            log.debug("Skip persist: construction heuristic in progress (initScore < 0). Score={}", best.getScore());
            return;
        }
        // ハード制約違反チェック
        if (best.getScore() != null && best.getScore().hardScore() < 0) {
            log.error("🚨 HARD CONSTRAINT VIOLATION DETECTED! Score: {}", best.getScore());
            log.error("🚫 Database save BLOCKED due to constraint violations");
            log.error("📋 Please review and fix the following:");
            
            // 制約違反の詳細分析と改善提案を出力
            analyzeConstraintViolations(best);
            
            // ハード制約違反時は保存を実行しない
            return;
        }
        // 既存データをクリア（サイクル開始日〜+1ヶ月の範囲で消す）
        LocalDate cycleStart = best.getMonth();
        LocalDate from = cycleStart;           // サイクル開始日
        LocalDate to   = cycleStart.plusMonths(1); // 半開区間
        String store = best.getStoreCode();
        if (store != null) {
            if ("ATTENDANCE".equals(key.getStage())) {
                shiftAssignmentMapper.deleteByMonthAndStore(from, to, store);
            } else {
                registerAssignmentMapper.deleteByMonthAndStore(from, to, store);
                shiftAssignmentMapper.deleteByMonthAndStore(from, to, store);
            }
        } else {
            // 後方互換: storeCode が無い場合は従来の削除（非推奨）
            if ("ATTENDANCE".equals(key.getStage())) {
                shiftAssignmentMapper.deleteByProblemId(best.getProblemId());
            } else {
                registerAssignmentMapper.deleteByProblemId(best.getProblemId());
                shiftAssignmentMapper.deleteByProblemId(best.getProblemId());
            }
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

        if ("ATTENDANCE".equals(key.getStage())) {
            // 出勤のみ保存
            shiftAssignments.forEach(shiftAssignmentMapper::insert);
            log.info("Persisted attendance solution – shifts={}, score={}", shiftAssignments.size(), best.getScore());
            return;
        }

        // Group by employee, date, and register for register assignments
        Map<String, List<ShiftAssignmentPlanningEntity>> registerAssignmentsByEmployeeDateRegister = best.getAssignmentList().stream()
                .filter(a -> a.getAssignedEmployee() != null
                        && a.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.REGISTER_OP
                        && a.getRegisterNo() != null)
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

        // Department task assignments (DEPARTMENT_TASK) – merge by employee/date/department/task
        List<io.github.riemr.shift.infrastructure.persistence.entity.DepartmentTaskAssignment> deptTaskAssignments = new ArrayList<>();
        if (best.getDepartmentCode() != null) {
            Map<String, List<ShiftAssignmentPlanningEntity>> deptTaskGroups = best.getAssignmentList().stream()
                    .filter(a -> a.getAssignedEmployee() != null && a.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.DEPARTMENT_TASK)
                    .collect(Collectors.groupingBy(a -> String.join("@",
                            a.getAssignedEmployee().getEmployeeCode(),
                            a.getShiftDate().toString(),
                            best.getDepartmentCode(),
                            a.getTaskCode() == null ? "" : a.getTaskCode())));

            for (List<ShiftAssignmentPlanningEntity> group : deptTaskGroups.values()) {
                group.sort(Comparator.comparing(a -> a.getOrigin().getStartAt()));
                Date startAt = group.get(0).getOrigin().getStartAt();
                Date endAt = group.get(group.size() - 1).getOrigin().getEndAt();
                String employeeCode = group.get(0).getAssignedEmployee().getEmployeeCode();
                String storeCode = group.get(0).getOrigin().getStoreCode();
                String taskCode = group.get(0).getTaskCode();

                var ta = new io.github.riemr.shift.infrastructure.persistence.entity.DepartmentTaskAssignment();
                ta.setStoreCode(storeCode);
                ta.setDepartmentCode(best.getDepartmentCode());
                ta.setTaskCode(taskCode);
                ta.setEmployeeCode(employeeCode);
                ta.setStartAt(startAt);
                ta.setEndAt(endAt);
                ta.setCreatedBy("auto");
                deptTaskAssignments.add(ta);
            }
        }

        // -- DB に保存 --
        shiftAssignments.forEach(shiftAssignmentMapper::insert);
        mergedRegisterAssignments.forEach(registerAssignmentMapper::insert);
        if (best.getDepartmentCode() != null) {
            departmentTaskAssignmentMapper.deleteByMonthStoreAndDepartment(from, to, store, best.getDepartmentCode());
            deptTaskAssignments.forEach(departmentTaskAssignmentMapper::insert);
        }

        log.info("Persisted best solution – stage={}, shifts={}, registers={}, deptTasks={} (score={})",
                key.getStage(), shiftAssignments.size(), mergedRegisterAssignments.size(), deptTaskAssignments.size(), best.getScore());
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
    private LocalDate computeCycleStart(LocalDate anyDate) {
        int startDay = appSettingService.getShiftCycleStartDay();
        int dom = anyDate.getDayOfMonth();
        if (dom >= startDay) {
            return anyDate.withDayOfMonth(Math.min(startDay, anyDate.lengthOfMonth()));
        } else {
            LocalDate prev = anyDate.minusMonths(1);
            return prev.withDayOfMonth(Math.min(startDay, prev.lengthOfMonth()));
        }
    }
}
