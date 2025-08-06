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
    private final SolverManager<ShiftSchedule, Long> solverManager;
    private final ShiftScheduleRepository repository;
    private final RegisterAssignmentMapper registerAssignmentMapper;
    private final ShiftAssignmentMapper shiftAssignmentMapper;
    private final EmployeeRegisterSkillMapper employeeRegisterSkillMapper;
    private final EmployeeMapper employeeMapper;

    /* === Settings === */
    @Value("${shift.solver.spent-limit:PT2M}") // ISO‑8601 Duration (default 2 minutes)
    private Duration spentLimit;

    /* === Runtime State === */
    private final Map<Long, Instant> startMap = new ConcurrentHashMap<>();
    private final Map<Long, SolverJob<ShiftSchedule, Long>> jobMap = new ConcurrentHashMap<>();

    /* ===================================================================== */
    /* Public API                                                            */
    /* ===================================================================== */

    /**
     * 月次シフト計算を非同期で開始。
     * 既に同じ月のジョブが走っている場合はそのステータスを再利用する。
     */
    @Transactional
    public SolveTicket startSolveMonth(LocalDate month) {
        long problemId = toProblemId(month);

        // -- ジョブが既に存在する場合はチケットのみ再発行 --
        if (jobMap.containsKey(problemId)) {
            Instant started = startMap.get(problemId);
            return new SolveTicket(problemId,
                    started.toEpochMilli(),
                    started.plus(spentLimit).toEpochMilli());
        }

        // -- Solver 起動 (listen 方式) --
        SolverJob<ShiftSchedule, Long> job = solverManager.solve(
                problemId,
                this::loadProblem,
                this::persistResult,
                this::onError);
        jobMap.put(problemId, job);

        // -- 進捗トラッキング用メタ情報 --
        Instant start = Instant.now();
        startMap.put(problemId, start);

        return new SolveTicket(problemId,
                start.toEpochMilli(),
                start.plus(spentLimit).toEpochMilli());
    }

    /** 進捗バー用ステータス */
    public SolveStatusDto getStatus(Long problemId) {
        SolverStatus status = solverManager.getSolverStatus(problemId);
        Instant began = startMap.get(problemId);
        if (began == null) return new SolveStatusDto("UNKNOWN", 0, 0);

        long now = Instant.now().toEpochMilli();
        long finish = began.plus(spentLimit).toEpochMilli();
        int pct = (int) Math.min(100, ((now - began.toEpochMilli()) * 100) / (finish - began.toEpochMilli()));
        if (status == SolverStatus.NOT_SOLVING) pct = 100;

        return new SolveStatusDto(status.name(), pct, finish);
    }

    /** 計算終了後の最終解をフロント用 DTO に変換して返す */
    public List<ShiftAssignmentView> fetchResult(Long problemId) {
        SolverJob<ShiftSchedule, Long> job = jobMap.get(problemId);
        if (job == null) return List.of();

        try {
            ShiftSchedule solved = job.getFinalBestSolution();
            return solved.getAssignmentList().stream()
                    .map(a -> new ShiftAssignmentView(
                            a.getOrigin().getStartAt().toString(),
                            a.getOrigin().getEndAt().toString(),
                            a.getOrigin().getRegisterNo(),
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
    public List<ShiftAssignmentMonthlyView> fetchAssignmentsByMonth(LocalDate anyDayInMonth) {
        LocalDate from = anyDayInMonth.withDayOfMonth(1);
        LocalDate to   = from.plusMonths(1);  // 翌月 1 日 (半開区間)

        List<RegisterAssignment> assignments = registerAssignmentMapper.selectByMonth(from, to);

        return assignments.stream()
                .map(a -> new ShiftAssignmentMonthlyView(
                        toLocalDateTime(a.getStartAt()),
                        toLocalDateTime(a.getEndAt()),
                        a.getRegisterNo(),
                        a.getEmployeeCode(), // Set employeeCode
                        Optional.ofNullable(a.getEmployeeCode())
                                .map(c -> {
                                    var emp = employeeMapper.selectByPrimaryKey(c);
                                    return emp != null ? emp.getEmployeeName() : "";
                                })
                                .orElse("")
                ))
                .toList();
    }

    /** 月別出勤時間取得 - シフトアサインメント表示 */
    public List<ShiftAssignmentMonthlyView> fetchShiftsByMonth(LocalDate anyDayInMonth) {
        LocalDate from = anyDayInMonth.withDayOfMonth(1);
        LocalDate to   = from.plusMonths(1);  // 翌月 1 日 (半開区間)

        List<ShiftAssignment> shifts = shiftAssignmentMapper.selectByMonth(from, to);

        return shifts.stream()
                .map(s -> new ShiftAssignmentMonthlyView(
                        toLocalDateTime(s.getStartAt()),
                        toLocalDateTime(s.getEndAt()),
                        null, // No register for shift assignments
                        s.getEmployeeCode(),
                        Optional.ofNullable(s.getEmployeeCode())
                                .map(c -> {
                                    var emp = employeeMapper.selectByPrimaryKey(c);
                                    return emp != null ? emp.getEmployeeName() : "";
                                })
                                .orElse("")
                ))
                .toList();
    }

    public List<ShiftAssignmentView> fetchAssignmentsByDate(LocalDate date) {
        List<RegisterAssignment> assignments = registerAssignmentMapper.selectByDate(date);
        return assignments.stream()
                .map(a -> new ShiftAssignmentView(
                        Optional.ofNullable(a.getStartAt())
                                .map(d -> d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                                .orElse(""),
                        Optional.ofNullable(a.getEndAt())
                                .map(d -> d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                                .orElse(""),
                        a.getRegisterNo(),
                        Optional.ofNullable(a.getEmployeeCode()).orElse("")
                ))
                .toList();
    }

    /* ===================================================================== */
    /* Callback for solveAndListen                                            */
    /* ===================================================================== */

    /**
     * Solver が最初に呼び出す問題生成関数。
     * problemId は yyyyMM の long 値で渡される。
     */
    private ShiftSchedule loadProblem(Long problemId) {
        LocalDate month = toMonth(problemId);
        ShiftSchedule unsolved = repository.fetchShiftSchedule(month);
        unsolved.setEmployeeRegisterSkillList(employeeRegisterSkillMapper.selectByExample(null));
        // Repository 側で必要なフィールドをセット済みだが、問題 ID だけはここで上書きしておく
        unsolved.setProblemId(problemId);
        if (unsolved.getAssignmentList() == null) {
            unsolved.setAssignmentList(new ArrayList<>());
        }
        log.info("Loaded unsolved problem for {} ({} assignments)", month, unsolved.getAssignmentList().size());
        return unsolved;
    }

    /**
     * 新しいベスト解が到着する度に呼び出され、DB に永続化する。
     * shift_assignmentテーブルには出勤時間を、register_assignmentテーブルにはレジアサイン時間を保存する。
     */
    private void persistResult(ShiftSchedule best) {
        // 既存データをクリア
        registerAssignmentMapper.deleteByProblemId(best.getProblemId());
        shiftAssignmentMapper.deleteByProblemId(best.getProblemId());

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
     * Solver 実行中に例外が発生した場合のハンドラ。
     */
    private void onError(Long problemId, Throwable throwable) {
        log.error("Solver failed for problem {}", problemId, throwable);
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