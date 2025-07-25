package io.github.riemr.shift.optimization.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.optaplanner.core.api.solver.SolverJob;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.api.solver.SolverStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.riemr.shift.application.dto.ShiftAssignmentView;
import io.github.riemr.shift.application.dto.SolveStatusDto;
import io.github.riemr.shift.application.dto.SolveTicket;
import io.github.riemr.shift.domain.ShiftAssignment;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import io.github.riemr.shift.repository.ShiftScheduleRepository;
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
    private final ShiftAssignmentMapper shiftAssignmentMapper;

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
        SolverJob<ShiftSchedule, Long> job = solverManager.solveAndListen(
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

    public List<ShiftAssignmentView> fetchAssignmentsByMonth(LocalDate month) {
        LocalDate from = month.withDayOfMonth(1);
        LocalDate to = month.plusMonths(1).withDayOfMonth(1);
        List<ShiftAssignment> assignments = shiftAssignmentMapper.selectByMonth(from, to);
        return assignments.stream()
                .map(a -> new ShiftAssignmentView(
                        Optional.ofNullable(a.getStartAt())
                                .map(date -> date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                                .orElse(""),
                        Optional.ofNullable(a.getEndAt())
                                .map(date -> date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                                .orElse(""),
                        a.getRegisterNo(),
                        Optional.ofNullable(a.getEmployeeCode()).orElse("")
                ))
                .toList();
    }

    public List<ShiftAssignmentView> fetchAssignmentsByDate(LocalDate date) {
        List<ShiftAssignment> assignments = shiftAssignmentMapper.selectByDate(date);
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
     * <p>
     * ここでは極力単純な実装として全件 Upsert を行うが、
     * 実運用では差分更新に切り替える方が性能的に好ましい。
     */
    private void persistResult(ShiftSchedule best) {
        shiftAssignmentMapper.deleteByProblemId(best.getProblemId());
        // -- 中間エンティティから確定したアサイン結果を抽出し、DB モデルに反映 --
        List<ShiftAssignment> entities = best.getAssignmentList().stream()
                .map(planningEntity -> {
                    ShiftAssignment origin = planningEntity.getOrigin();
                    Optional.ofNullable(planningEntity.getAssignedEmployee())
                            .ifPresent(employee -> {
                                origin.setEmployeeCode(employee.getEmployeeCode());
                                origin.setShiftId(null); // 常に INSERT
                            });
                    return origin;
                })
                .toList();

        // -- DB に Upsert --
        entities.forEach(shiftAssignmentMapper::insert);
        log.info("Persisted best solution – {} assignments saved (score = {})", entities.size(), best.getScore());
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