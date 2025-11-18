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
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandIntervalMapper;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import io.github.riemr.shift.optimization.solution.AttendanceSolution;
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
    private final SolverManager<AttendanceSolution, ProblemKey> attendanceSolverManager;
    private final ShiftScheduleRepository repository;
    private final RegisterAssignmentMapper registerAssignmentMapper;
    private final ShiftAssignmentMapper shiftAssignmentMapper;
    private final DepartmentTaskAssignmentMapper departmentTaskAssignmentMapper;
    private final RegisterDemandIntervalMapper registerDemandIntervalMapper;
    private final EmployeeRegisterSkillMapper employeeRegisterSkillMapper;
    private final EmployeeMapper employeeMapper;
    private final AppSettingService appSettingService;
    private final TaskPlanService taskPlanService;
    private final PlatformTransactionManager transactionManager;

    /* === Settings === */
    @Value("${shift.solver.spent-limit:PT5M}") // ISO‑8601 Duration (default 5 minutes)
    private Duration spentLimit;
    // 終了条件（未改善時間）は OptaPlanner の TerminationConfig で設定

    /* === Runtime State === */
    private final Map<ProblemKey, Instant> startMap = new ConcurrentHashMap<>();
    private final Map<ProblemKey, Object> jobMap = new ConcurrentHashMap<>();
    private final Map<ProblemKey, String> currentPhaseMap = new ConcurrentHashMap<>(); // 現在のフェーズ
    // 開発者向け: スコア推移の時系列
    private final Map<ProblemKey, List<io.github.riemr.shift.application.dto.ScorePoint>> scoreSeriesMap = new ConcurrentHashMap<>();
    // 進行状況可視化用のスコア系列のみ保持
    // UUIDチケット -> ProblemKey の対応
    private final Map<String, ProblemKey> ticketKeyMap = new ConcurrentHashMap<>();
    // ProblemKey -> 代表チケットID（同一キーの再実行は同一ticketIdを返す）
    private final Map<ProblemKey, String> keyTicketMap = new ConcurrentHashMap<>();

    /* ===================================================================== */
    /* Public API                                                            */
    /* ===================================================================== */


    /**
     * 指定された月の月次シフト計算を非同期で開始する（店舗・部門指定なし）。
     * 
     * @param month 計算対象の月（例：2025-05-01）
     * @return 最適化ジョブの制御チケット（進捗確認やキャンセルに使用）
     * @see #startSolveMonth(LocalDate, String, String) 店舗・部門指定版
     */
    public SolveTicket startSolveMonth(LocalDate month) {
        return startSolveMonth(month, null, null);
    }

    /**
     * 指定された月と店舗の月次シフト計算を非同期で開始する。
     * 既に同じ月のジョブが実行中の場合はそのステータスを再利用する。
     * 
     * @param month 計算対象の月（例：2025-05-01）
     * @param storeCode 対象店舗コード（nullまたは空文字の場合は全店舗対象）
     * @return 最適化ジョブの制御チケット（進捗確認やキャンセルに使用）
     * @see #startSolveMonth(LocalDate) 店舗指定なし版
     * @see #startSolveMonth(LocalDate, String, String) 部門指定版
     */
    public SolveTicket startSolveMonth(LocalDate month, String storeCode) {
        return startSolveMonth(month, storeCode, null);
    }

    /**
     * 指定された月、店舗、部門の月次シフト計算を非同期で開始する。
     * 既に同じ月のジョブが実行中の場合はそのステータスを再利用する。
     * 
     * <p>このメソッドは最も詳細な制御を提供し、以下の前処理も自動実行する：</p>
     * <ul>
     *   <li>作業計画の物質化（TaskPlanService経由）</li>
     *   <li>部門タスク割当の物質化</li>
     *   <li>作業需要データの生成</li>
     * </ul>
     * 
     * @param month 計算対象の月（例：2025-05-01）
     * @param storeCode 対象店舗コード（nullまたは空文字の場合は全店舗対象）
     * @param departmentCode 対象部門コード（nullまたは空文字の場合は全部門対象）
     * @return 最適化ジョブの制御チケット（進捗確認やキャンセルに使用）
     * @see #startSolveMonth(LocalDate) 最もシンプルな版
     * @see #startSolveMonth(LocalDate, String) 店舗指定版
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = false)
    public SolveTicket startSolveMonth(LocalDate month, String storeCode, String departmentCode) {
        return startSolveInternal(month, storeCode, departmentCode, "ASSIGNMENT");
    }

    /**
     * 指定された月の出勤パターン最適化を非同期で開始する。
     * 
     * <p>このメソッドは通常のシフト割当とは異なる「勤怠パターン」最適化を実行する。
     * 日単位での出勤パターン（DailyPatternAssignmentEntity）を従業員に割り当てることで、
     * より高レベルなシフト計画を行う。</p>
     * 
     * @param month 計算対象の月（例：2025-05-01）
     * @param storeCode 対象店舗コード（nullまたは空文字の場合は全店舗対象）
     * @param departmentCode 対象部門コード（nullまたは空文字の場合は全部門対象）
     * @return 最適化ジョブの制御チケット（進捗確認やキャンセルに使用）
     * @see #startSolveMonth(LocalDate, String, String) 通常のシフト割当版
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = false)
    public SolveTicket startSolveAttendanceMonth(LocalDate month, String storeCode, String departmentCode) {
        return startSolveInternal(month, storeCode, departmentCode, "ATTENDANCE");
    }

    /**
     * 内部用: 最適化ジョブを実際に起動する共通処理。
     * 
     * @param month 対象月
     * @param storeCode 店舗コード
     * @param departmentCode 部門コード 
     * @param stage 最適化ステージ（"ASSIGNMENT" または "ATTENDANCE"）
     * @return 最適化ジョブの制御チケット
     */
    private SolveTicket startSolveInternal(LocalDate month, String storeCode, String departmentCode, String stage) {
        String ticketId = java.util.UUID.randomUUID().toString();
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

        // 既存ジョブがある場合の扱い：
        // 実行中なら再利用、停止済み（NOT_SOLVING）ならエントリをクリアして再起動する
        if (jobMap.containsKey(key)) {
            SolverStatus st = solverManager.getSolverStatus(key);
            if (st != null && st != SolverStatus.NOT_SOLVING) {
                Instant started = startMap.get(key);
                if (started == null) {
                    started = Instant.now();
                    startMap.put(key, started);
                }
                // 既存ジョブが走っている場合は既存のticketIdを返す（なければ今のticketIdで登録）
                String existing = keyTicketMap.computeIfAbsent(key, k -> {
                    ticketKeyMap.put(ticketId, k);
                    return ticketId;
                });
                return new SolveTicket(existing,
                        started.toEpochMilli(),
                        started.plus(spentLimit).toEpochMilli());
            } else {
                // 前回の終了状態をクリアして再起動
                jobMap.remove(key);
                startMap.remove(key);
                currentPhaseMap.remove(key);
            }
        }

        // 進捗メタ情報（レース防止のため先に開始時刻を記録）
        Instant start = Instant.now();
        startMap.put(key, start);

        // Solver 起動 (listen)
        currentPhaseMap.put(key, "初期解生成中");
        if ("ATTENDANCE".equals(stage)) {
            log.error("=== STARTING ATTENDANCE OPTIMIZATION ==="); // デバッグ用
            SolverJob<AttendanceSolution, ProblemKey> job = attendanceSolverManager.solveAndListen(
                    key,
                    this::loadAttendanceProblem,
                    best -> {
                        // 進捗更新・スコア記録（表示用）
                        if (best != null && best.getScore() != null) {
                            log.debug("ATTENDANCE CALLBACK: score={}, assignments={}", 
                                    best.getScore(),
                                    best.getPatternAssignments().stream()
                                            .filter(p -> p.getAssignedEmployee() != null).count());
                            updatePhaseScore(key, best.getScore());
                            recordScorePointGeneric(key, best.getScore());
                        } else {
                            log.warn("ATTENDANCE CALLBACK: best solution is null or has no score");
                        }
                        // 中間ベストは保存しない（最終ベストのみ保存）
                    },
                    this::onError);
            jobMap.put(key, job);
            // 未改善による早期終了は TerminationConfig に委譲

            // 最終ベストのみ保存
            new Thread(() -> {
                try {
                    var finalBest = job.getFinalBestSolution();
                    log.info("ATTENDANCE final solution received: score={}, patterns={}", 
                            finalBest != null ? finalBest.getScore() : "null",
                            finalBest != null && finalBest.getPatternAssignments() != null ? finalBest.getPatternAssignments().size() : 0);
                    
                    // 最終スコアも強制記録（画面表示用）
                    if (finalBest != null && finalBest.getScore() != null) {
                        recordScorePointGeneric(key, finalBest.getScore());
                        log.info("FINAL ATTENDANCE SCORE RECORDED: {}", finalBest.getScore());
                    }
                    
                    // デバッグ: 従業員別の割り当て状況を出力
                    if (finalBest != null && finalBest.getPatternAssignments() != null) {
                        var assignedPatterns = finalBest.getPatternAssignments().stream()
                                .filter(p -> p.getAssignedEmployee() != null)
                                .collect(java.util.stream.Collectors.groupingBy(
                                        p -> p.getAssignedEmployee().getEmployeeCode(),
                                        java.util.stream.Collectors.counting()));
                        log.info("ATTENDANCE assignment by employee: {}", assignedPatterns);
                        
                        if (assignedPatterns.isEmpty()) {
                            log.warn("No patterns assigned to any employee - analyzing first few patterns:");
                            finalBest.getPatternAssignments().stream()
                                    .limit(5)
                                    .forEach(p -> log.warn("Pattern: date={}, time={}-{}, candidates={}, assigned={}", 
                                            p.getDate(), p.getPatternStart(), p.getPatternEnd(),
                                            p.getCandidateEmployees() != null ? p.getCandidateEmployees().size() : "null",
                                            p.getAssignedEmployee()));
                        }
                    }
                    
                    TransactionTemplate tt = new TransactionTemplate(transactionManager);
                    tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tt.execute(s -> { persistAttendanceResult(finalBest, key); return null; });
                } catch (Exception e) {
                    log.error("Persist(final attendance) failed: {}", e.getMessage(), e);
                }
            }, "attendance-persist-" + key.hashCode()).start();
        } else {
            SolverJob<ShiftSchedule, ProblemKey> job = solverManager.solveAndListen(
                    key,
                    this::loadProblem,
                    bestSolution -> {
                        // フェーズ・スコアの更新（表示用）
                        updatePhase(key, bestSolution);
                        recordScorePoint(key, bestSolution);
                        // 中間ベストは保存しない（最終ベストのみ保存）
                    },
                    this::onError);
            jobMap.put(key, job);
            // 未改善による早期終了は TerminationConfig に委譲

            // 最終ベストのみ保存
            new Thread(() -> {
                try {
                    var finalBest = job.getFinalBestSolution();
                    TransactionTemplate tt = new TransactionTemplate(transactionManager);
                    tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tt.execute(s -> { persistResult(finalBest, key); return null; });
                } catch (Exception e) {
                    log.error("Persist(final assign) failed: {}", e.getMessage(), e);
                }
            }, "assign-persist-" + key.hashCode()).start();
        }

        // チケットとキーの対応を登録
        ticketKeyMap.put(ticketId, key);
        keyTicketMap.put(key, ticketId);
        return new SolveTicket(ticketId,
                start.toEpochMilli(),
                start.plus(spentLimit).toEpochMilli());
    }

    /**
     * 最適化ジョブの現在の進捗ステータスを取得する。
     * 
     * <p>進捗バーの表示や、ポーリングでの状態確認に使用される。
     * ステージ指定がない場合は、該当する最初のジョブのステータスを返却する。</p>
     * 
     * @param problemId 問題ID（月をlong型で表現、例：202505）
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @param departmentCode 部門コード（nullの場合は全部門対象）
     * @return 現在のステータス情報（実行状態、進捗率、経過時間等）
     * @see #getStatus(Long, String, String, String) ステージ指定版
     */
    public SolveStatusDto getStatus(String ticketId, String storeCode, String departmentCode) {
        ProblemKey key = ticketKeyMap.get(ticketId);
        if (key != null) return internalStatus(key);
        return new SolveStatusDto("UNKNOWN", 0, 0, "未開始");
    }

    /**
     * 最適化ジョブの現在の進捗ステータスを取得する（ステージ指定版）。
     * 
     * <p>特定のステージ（ASSIGNMENT または ATTENDANCE）を指定して、
     * より精密にジョブを特定できる。</p>
     * 
     * @param problemId 問題ID（月をlong型で表現、例：202505）
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @param departmentCode 部門コード（nullの場合は全部門対象）
     * @param stage 最適化ステージ（"ASSIGNMENT" または "ATTENDANCE"）
     * @return 現在のステータス情報（実行状態、進捗率、経過時間等）
     * @see #getStatus(Long, String, String) ステージ指定なし版
     */
    public SolveStatusDto getStatus(String ticketId, String storeCode, String departmentCode, String stage) {
        ProblemKey key = ticketKeyMap.get(ticketId);
        if (key != null) return internalStatus(key);
        return new SolveStatusDto("UNKNOWN", 0, 0, "未開始");
    }

    private SolveStatusDto internalStatus(ProblemKey key) {
        SolverStatus status = solverManager.getSolverStatus(key);
        if (status == null || status == SolverStatus.NOT_SOLVING) {
            SolverStatus alt = attendanceSolverManager.getSolverStatus(key);
            if (alt != null) status = alt;
        }
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
        String currentPhase = currentPhaseMap.get(key);
        if (status == SolverStatus.SOLVING_ACTIVE) {
            // ユーザー体感改善: 一定時間経過後は「最適化中」に移行とみなす
            if (currentPhase == null || "初期化中".equals(currentPhase) || "初期解生成中".equals(currentPhase)) {
                if (System.currentTimeMillis() - start > 2000) {
                    currentPhase = "最適化中";
                    currentPhaseMap.put(key, currentPhase);
                } else if (currentPhase == null) {
                    currentPhase = "初期解生成中";
                }
            }
        } else if (status == SolverStatus.NOT_SOLVING) {
            if (currentPhase == null) currentPhase = "完了";
            // 完了したのでクリーンアップ
            startMap.remove(key);
            currentPhaseMap.remove(key);
        }

        return new SolveStatusDto(status == null ? "UNKNOWN" : status.name(), pct, finish, currentPhase == null ? "完了" : currentPhase);
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
        // 改善検出は OptaPlanner の終了条件に委譲（記録のみ）
        // keep last 1000 points to bound memory
        var list = scoreSeriesMap.get(key);
        if (list.size() > 1000) {
            list.subList(0, list.size() - 1000).clear();
        }
    }

    // ATTENDANCE 用（スコアのみからポイントを作成）
    private void recordScorePointGeneric(ProblemKey key, HardSoftScore s) {
        if (s == null) return;
        int init = s.initScore();
        int hard = s.hardScore();
        int soft = s.softScore();
        long now = System.currentTimeMillis();
        scoreSeriesMap.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new io.github.riemr.shift.application.dto.ScorePoint(now, init, hard, soft));
        log.debug("SCORE RECORDED: key={}, score={}hard/{}soft, points_count={}", 
                key, hard, soft, scoreSeriesMap.get(key).size());
        // 改善検出は OptaPlanner の終了条件に委譲（記録のみ）
        var list = scoreSeriesMap.get(key);
        if (list.size() > 1000) {
            list.subList(0, list.size() - 1000).clear();
        }
    }

    // 早期終了（未改善）は OptaPlanner の TerminationConfig.withUnimprovedScoreSpentLimit に委譲

    /**
     * 開発者向け: 最適化過程のスコア推移時系列データを取得する。
     * 
     * <p>最適化中にリアルタイムで収集されるスコア値（ハード制約違反数、ソフト制約スコア）の
     * 時系列データを返却する。デバッグや最適化性能分析に使用される。</p>
     * 
     * <p>注意: departmentCodeのnullと空文字列は同一として扱われる。</p>
     * 
     * @param problemId 問題ID（月をlong型で表現、例：202505）
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @param departmentCode 部門コード（nullまたは空文字の場合は全部門対象）
     * @return スコア推移のリスト（時系列順）
     */
    public List<io.github.riemr.shift.application.dto.ScorePoint> getScoreSeries(String ticketId, String storeCode, String departmentCode) {
        ProblemKey key = ticketKeyMap.get(ticketId);
        if (key == null) {
            log.debug("SCORE SERIES: ticketId {} not found", ticketId);
            return List.of();
        }
        var scores = scoreSeriesMap.getOrDefault(key, List.of());
        log.debug("SCORE SERIES: ticketId={}, key={}, points_count={}", ticketId, key, scores.size());
        return scores;
    }

    /**
     * 最適化計算終了後の最終解をフロントエンド用DTOに変換して返す。
     * 
     * <p>OptaPlannerによる最適化結果を、UI表示やAPIレスポンス用のデータ形式に変換する。
     * ステージ指定がない場合は、最初に見つかったジョブの結果を返却する。</p>
     * 
     * @param problemId 問題ID（月をlong型で表現、例：202505）
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @param departmentCode 部門コード（nullの場合は全部門対象）
     * @return シフト割当結果のビューリスト
     * @see #fetchResult(Long, String, String, String) ステージ指定版
     */
    public List<ShiftAssignmentView> fetchResult(String ticketId, String storeCode, String departmentCode) {
        ProblemKey k = ticketKeyMap.get(ticketId);
        if (k != null) return internalFetchResult(k);
        return List.of();
    }

    /**
     * 最適化計算終了後の最終解をフロントエンド用DTOに変換して返す（ステージ指定版）。
     * 
     * <p>特定のステージ（ASSIGNMENT または ATTENDANCE）を指定して、
     * より精密にジョブを特定し、その結果を取得する。</p>
     * 
     * @param problemId 問題ID（月をlong型で表現、例：202505）
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @param departmentCode 部門コード（nullの場合は全部門対象）
     * @param stage 最適化ステージ（"ASSIGNMENT" または "ATTENDANCE"）
     * @return シフト割当結果のビューリスト
     * @see #fetchResult(Long, String, String) ステージ指定なし版
     */
    public List<ShiftAssignmentView> fetchResult(String ticketId, String storeCode, String departmentCode, String stage) {
        ProblemKey k = ticketKeyMap.get(ticketId);
        if (k != null) return internalFetchResult(k);
        return List.of();
    }

    private List<ShiftAssignmentView> internalFetchResult(ProblemKey key) {
        Object anyJob = jobMap.get(key);
        if (anyJob == null) return List.of();
        if (!(anyJob instanceof SolverJob)) return List.of();
        @SuppressWarnings("unchecked")
        SolverJob<ShiftSchedule, ProblemKey> job = null;
        try {
            job = (SolverJob<ShiftSchedule, ProblemKey>) anyJob;
        } catch (ClassCastException ex) {
            // ATTENDANCE ジョブの場合は結果形式が異なるため空を返す
            return List.of();
        }
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

    /**
     * 指定された月のシフト割当データを取得する（月次表示用）。
     * 
     * <p>レジ割当または部門タスク割当のデータを、月間カレンダー表示用の形式で返却する。
     * 部門コードが "520" 以外の場合は部門タスク割当、それ以外はレジ割当データを取得する。</p>
     * 
     * @param anyDayInMonth 対象月内の任意の日付（月の特定に使用）
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @param departmentCode 部門コード（nullまたは"520"の場合はレジ割当、それ以外は部門タスク割当）
     * @return 月次シフト割当ビューのリスト
     * @see #fetchAssignmentsByMonth(LocalDate, String) 部門指定なし版
     */
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

    /**
     * 指定された月のシフト割当データを取得する（部門指定なし版）。
     * 
     * @param anyDayInMonth 対象月内の任意の日付
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @return 月次シフト割当ビューのリスト
     * @see #fetchAssignmentsByMonth(LocalDate, String, String) 部門指定版
     */
    public List<ShiftAssignmentMonthlyView> fetchAssignmentsByMonth(LocalDate anyDayInMonth, String storeCode) {
        return fetchAssignmentsByMonth(anyDayInMonth, storeCode, null);
    }

    /**
     * 指定された月のシフト出勤時間データを取得する。
     * 
     * <p>ShiftAssignmentテーブルから出勤時間データを取得し、
     * 月間カレンダー表示用の形式で返却する。</p>
     * 
     * @param anyDayInMonth 対象月内の任意の日付（月の特定に使用）
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @param departmentCode 部門コード（nullの場合は全部門対象）
     * @return 月次シフト出勤時間ビューのリスト
     * @see #fetchShiftsByMonth(LocalDate, String) 部門指定なし版
     */
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

    /**
     * 指定された日付のシフト割当データを取得する。
     * 
     * <p>部門コードが指定されている場合は部門タスク割当、
     * そうでなければレジ割当データを返却する。</p>
     * 
     * @param date 対象日付
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @param departmentCode 部門コード（nullの場合はレジ割当、指定ありの場合は部門タスク割当）
     * @return シフト割当ビューのリスト
     * @see #fetchAssignmentsByDate(LocalDate, String) 部門指定なし版
     */
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

    /**
     * 指定された日付のシフト割当データを取得する（部門指定なし版）。
     * 
     * @param date 対象日付
     * @param storeCode 店舗コード（nullの場合は全店舗対象）
     * @return シフト割当ビューのリスト
     * @see #fetchAssignmentsByDate(LocalDate, String, String) 部門指定版
     */
    public List<ShiftAssignmentView> fetchAssignmentsByDate(LocalDate date, String storeCode) {
        return fetchAssignmentsByDate(date, storeCode, null);
    }

    /**
     * シフト割当の変更をデータベースに保存する。
     * 
     * <p>フロントエンドからのシフト割当変更リクエストを受け取り、
     * 15分スロット単位でレジ割当データを更新または挿入する。</p>
     * 
     * @param request シフト割当変更リクエスト（日付、変更リストを含む）
     */
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
    /**
     * 最適化問題データをデータベースから読み込み、ShiftScheduleソリューションオブジェクトを構築する。
     * 
     * @param key 問題キー（月、店舗、部門情報を含む）
     * @return 構築されたShiftScheduleソリューション
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
        if (unsolved.getAssignmentList() == null) unsolved.setAssignmentList(new ArrayList<>());
        if (unsolved.getBreakList() == null) unsolved.setBreakList(new ArrayList<>());

        // ステージをエンティティへ伝搬
        if (unsolved.getAssignmentList() != null) {
            for (var a : unsolved.getAssignmentList()) {
                a.setStage(key.getStage());
            }
        }

        // フォールバック: エンティティが空の場合、ATTENDANCE用に需要から最小限の枠を合成
        if ((unsolved.getAssignmentList() == null || unsolved.getAssignmentList().isEmpty())
                && "ATTENDANCE".equalsIgnoreCase(key.getStage())) {
            int synthesized = synthesizeAttendanceSlotsFromDemand(unsolved);
            log.warn("Assignment list was empty. Synthesized {} attendance slots from demand for store={}, dept={}.",
                    synthesized, unsolved.getStoreCode(), unsolved.getDepartmentCode());
        }

        // 休憩候補（BreakAssignment）を生成
        try {
            prepareBreakAssignments(unsolved, key.getStage(), cycleStart);
        } catch (Exception ex) {
            log.warn("Failed to prepare break assignments: {}", ex.getMessage());
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

    private AttendanceSolution loadAttendanceProblem(ProblemKey key) {
        log.error("=== LOAD ATTENDANCE PROBLEM STARTED ==="); // デバッグ用
        LocalDate cycleStart = key.getCycleStart();
        ShiftSchedule base = repository.fetchShiftSchedule(cycleStart, key.getStoreCode(), key.getDepartmentCode());
        AttendanceSolution sol = new AttendanceSolution();
        sol.setProblemId(toProblemId(cycleStart));
        sol.setMonth(cycleStart);
        sol.setStoreCode(key.getStoreCode());
        sol.setDepartmentCode(key.getDepartmentCode());
        sol.setEmployeeList(base.getEmployeeList());
        sol.setEmployeeShiftPatternList(base.getEmployeeShiftPatternList());
        sol.setEmployeeWeeklyPreferenceList(base.getEmployeeWeeklyPreferenceList());
        sol.setEmployeeRequestList(base.getEmployeeRequestList());
        sol.setEmployeeMonthlySettingList(base.getEmployeeMonthlySettingList());
        sol.setDemandList(base.getDemandList());
        var patterns = buildPatternAssignmentsFromDemand(sol);
        sol.setPatternAssignments(patterns);
        
        // デバッグ用: パターン詳細を出力
        long assignedCount = patterns.stream().filter(p -> p.getAssignedEmployee() != null).count();
        long unassignedCount = patterns.size() - assignedCount;
        log.info("Generated {} pattern assignments for ATTENDANCE optimization (assigned: {}, unassigned: {})", 
                patterns.size(), assignedCount, unassignedCount);
        
        // 需要と供給の詳細分析
        var demand = Optional.ofNullable(sol.getDemandList()).orElse(List.of());
        int totalDemandUnits = demand.stream().mapToInt(io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter::getRequiredUnits).sum();
        log.info("Total demand units: {}, Total pattern slots: {}, Coverage: {:.1f}%", 
                totalDemandUnits, patterns.size(), (double)patterns.size() / totalDemandUnits * 100);
        
        return sol;
    }

    private List<io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity> buildPatternAssignmentsFromDemand(
            AttendanceSolution sol) {
        List<DailyPatternAssignmentEntity> result = new ArrayList<>();
        var patterns = Optional.ofNullable(sol.getEmployeeShiftPatternList()).orElse(List.of());
        var demand = Optional.ofNullable(sol.getDemandList()).orElse(List.of());
        var employees = Optional.ofNullable(sol.getEmployeeList()).orElse(List.of());
        var weeklyPrefs = Optional.ofNullable(sol.getEmployeeWeeklyPreferenceList()).orElse(List.of());
        var requests = Optional.ofNullable(sol.getEmployeeRequestList()).orElse(List.of());
        if (patterns.isEmpty()) return result;
        ZoneId zone = ZoneId.systemDefault();

        Map<java.time.LocalDate, List<io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter>> demandByDate = new HashMap<>();
        for (var d : demand) {
            java.util.Date dd = d.getDemandDate();
            java.time.LocalDate dt;
            if (dd instanceof java.sql.Date) {
                dt = ((java.sql.Date) dd).toLocalDate();
            } else {
                dt = dd.toInstant().atZone(zone).toLocalDate();
            }
            demandByDate.computeIfAbsent(dt, k -> new java.util.ArrayList<>()).add(d);
        }

        // パターン時間帯の重複（同時刻の開始/終了が複数従業員に跨る）を除外し、時間窓のユニーク集合を作る
        // ただし priority が 0,1 のパターンは「割当不可」として窓生成の対象外にする（>=2 のみ採用）
        java.util.LinkedHashSet<String> windowKeys = new java.util.LinkedHashSet<>();
        List<java.time.LocalTime[]> windows = new java.util.ArrayList<>();
        for (var p : patterns) {
            if (Boolean.FALSE.equals(p.getActive())) continue;
            Short prio = p.getPriority();
            if (prio == null || prio.intValue() < 2) continue; // priority 0,1 は除外
            var ps = p.getStartTime().toLocalTime();
            var pe = p.getEndTime().toLocalTime();
            String key = ps + "_" + pe;
            if (windowKeys.add(key)) {
                windows.add(new java.time.LocalTime[]{ps, pe});
            }
        }

        var cycleStart = sol.getMonth();
        var cycleEnd = cycleStart.plusMonths(1);
        for (var date = cycleStart; date.isBefore(cycleEnd); date = date.plusDays(1)) {
            var quarters = demandByDate.getOrDefault(date, List.of());
            for (var win : windows) {
                var ps = win[0];
                var pe = win[1];
                int maxUnits = 0;
                for (var q : quarters) {
                    var qt = q.getSlotTime();
                    if ((qt.equals(ps) || qt.isAfter(ps)) && qt.isBefore(pe)) {
                        maxUnits = Math.max(maxUnits, Optional.ofNullable(q.getRequiredUnits()).orElse(0));
                    }
                }
                for (int i = 0; i < maxUnits; i++) {
                    String id = sol.getStoreCode() + "|" + sol.getDepartmentCode() + "|" + date + "|" + ps + "|" + pe + "|" + i;
                    var ent = new io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity(
                            id, sol.getStoreCode(), sol.getDepartmentCode(), date, ps, pe, i);
                    // 候補従業員（パターン境界完全一致＋週次/希望休/基本時間内）を事前計算
                    var candidates = computeEligibleEmployeesForWindow(employees, patterns, weeklyPrefs, requests, date, ps, pe);
                    ent.setCandidateEmployees(candidates);
                    
                    if (i == 0) { // デバッグログは最初のユニットのみ
                        log.debug("Pattern {}-{} on {}: {} eligible employees", ps, pe, date, candidates.size());
                    }
                    result.add(ent);
                }
            }
        }
        return result;
    }

    private List<io.github.riemr.shift.infrastructure.persistence.entity.Employee> computeEligibleEmployeesForWindow(
            List<io.github.riemr.shift.infrastructure.persistence.entity.Employee> employees,
            List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern> patterns,
            List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference> weeklyPrefs,
            List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest> requests,
            java.time.LocalDate date,
            java.time.LocalTime ps,
            java.time.LocalTime pe) {
        Map<String, List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern>> pattByEmp =
                patterns.stream().collect(java.util.stream.Collectors.groupingBy(io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern::getEmployeeCode));
        Map<String, List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference>> weeklyByEmp =
                weeklyPrefs.stream().collect(java.util.stream.Collectors.groupingBy(io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference::getEmployeeCode));
        Map<String, Set<java.time.LocalDate>> offByEmp = new HashMap<>();
        for (var r : requests) {
            if (r.getRequestKind() == null) continue;
            String kind = r.getRequestKind().toLowerCase();
            if ("off".equals(kind) || "paid_leave".equals(kind)) {
                java.time.LocalDate d = (r.getRequestDate() instanceof java.sql.Date)
                        ? ((java.sql.Date) r.getRequestDate()).toLocalDate()
                        : r.getRequestDate().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                offByEmp.computeIfAbsent(r.getEmployeeCode(), k -> new java.util.HashSet<>()).add(d);
            }
        }
        int dow = date.getDayOfWeek().getValue();
        List<io.github.riemr.shift.infrastructure.persistence.entity.Employee> list = new java.util.ArrayList<>();
        for (var e : employees) {
            String code = e.getEmployeeCode();
            // パターン境界完全一致 かつ priority >= 2
            boolean hasPattern = pattByEmp.getOrDefault(code, List.of()).stream()
                    .anyMatch(p -> !Boolean.FALSE.equals(p.getActive())
                            && p.getPriority() != null && p.getPriority().intValue() >= 2
                            && p.getStartTime().toLocalTime().equals(ps)
                            && p.getEndTime().toLocalTime().equals(pe));
            if (!hasPattern) continue;
            // 希望休
            if (offByEmp.getOrDefault(code, Set.of()).contains(date)) continue;
            // 週次OFF/基本時間
            boolean weeklyOk = true;
            for (var w : weeklyByEmp.getOrDefault(code, List.of())) {
                if (w.getDayOfWeek() == null || w.getDayOfWeek().intValue() != dow) continue;
                if ("OFF".equalsIgnoreCase(w.getWorkStyle())) { weeklyOk = false; break; }
                if (w.getBaseStartTime() != null && w.getBaseEndTime() != null) {
                    var bs = w.getBaseStartTime().toLocalTime();
                    var be = w.getBaseEndTime().toLocalTime();
                    if (ps.isBefore(bs) || pe.isAfter(be)) { weeklyOk = false; break; }
                }
            }
            if (!weeklyOk) continue;
            list.add(e);
        }
        return list;
    }

    private void persistAttendanceResult(AttendanceSolution best, ProblemKey key) {
        if (best == null || best.getPatternAssignments() == null) {
            log.warn("Cannot persist attendance result - solution or pattern assignments are null");
            return;
        }
        
        long assignedPatterns = best.getPatternAssignments().stream().filter(p -> p.getAssignedEmployee() != null).count();
        log.info("Persisting attendance result: score={}, total_patterns={}, assigned_patterns={}", 
                best.getScore(), best.getPatternAssignments().size(), assignedPatterns);
        
        LocalDate from = best.getMonth();
        LocalDate to = from.plusMonths(1);
        String store = best.getStoreCode();
        if (store == null || store.isBlank()) throw new IllegalStateException("storeCode must not be null for attendance persist");
        int del = shiftAssignmentMapper.deleteByMonthAndStore(from, to, store);
        log.info("[attendance] Cleared shift_assignment rows: {} for store={}, from={}, to={}", del, store, from, to);

        ZoneId zone = ZoneId.systemDefault();
        int ins = 0;
        java.util.Set<String> dedup = new java.util.HashSet<>();
        for (var e : best.getPatternAssignments()) {
            if (e.getAssignedEmployee() == null) continue;
            var sa = new ShiftAssignment();
            sa.setStoreCode(store);
            sa.setEmployeeCode(e.getAssignedEmployee().getEmployeeCode());
            var startAt = java.util.Date.from(e.getDate().atTime(e.getPatternStart()).atZone(zone).toInstant());
            var endAt = java.util.Date.from(e.getDate().atTime(e.getPatternEnd()).atZone(zone).toInstant());
            sa.setStartAt(startAt);
            sa.setEndAt(endAt);
            sa.setCreatedBy("auto");
            // 一意キー (store_code, employee_code, start_at) の重複を事前に排除
            String k = store + "|" + sa.getEmployeeCode() + "|" + sa.getStartAt().getTime();
            if (dedup.add(k)) {
                // 競合時（同月・同従業員・同開始）の並行保存にも耐えるようUPSERTを使用
                shiftAssignmentMapper.upsert(sa);
                ins++;
            }
        }
        log.info("[attendance] Persisted rows: {} (from {} assigned patterns)", ins, assignedPatterns);
    }

    // RegisterDemandQuarter の requiredUnits に基づき、ATTENDANCE用に15分枠のプレースホルダを合成
    private int synthesizeAttendanceSlotsFromDemand(ShiftSchedule schedule) {
        var demand = Optional.ofNullable(schedule.getDemandList()).orElse(List.of());
        if (demand.isEmpty()) return 0;
        List<ShiftAssignmentPlanningEntity> list = new java.util.ArrayList<>();
        java.time.ZoneId zone = ZoneId.systemDefault();
        for (var q : demand) {
            if (schedule.getStoreCode() != null && !schedule.getStoreCode().equals(q.getStoreCode())) continue;
            if (q.getRequiredUnits() == null || q.getRequiredUnits() <= 0) continue;
            // 15分スロットの開始・終了
            java.time.LocalDateTime startLdt = java.time.LocalDateTime.of(
                    q.getDemandDate().toInstant().atZone(zone).toLocalDate(), q.getSlotTime());
            java.time.LocalDateTime endLdt = startLdt.plusMinutes(15);
            Date startAt = Date.from(startLdt.atZone(zone).toInstant());
            Date endAt = Date.from(endLdt.atZone(zone).toInstant());
            for (int i = 0; i < q.getRequiredUnits(); i++) {
                RegisterAssignment origin = new RegisterAssignment();
                origin.setStoreCode(q.getStoreCode());
                origin.setStartAt(startAt);
                origin.setEndAt(endAt);
                // registerNo は未使用（ATTENDANCEでは人数のみ評価）
                ShiftAssignmentPlanningEntity e = new ShiftAssignmentPlanningEntity(origin);
                e.setStage("ATTENDANCE");
                e.setDepartmentCode(schedule.getDepartmentCode());
                e.setWorkKind(io.github.riemr.shift.optimization.entity.WorkKind.REGISTER_OP);
                list.add(e);
            }
        }
        schedule.getAssignmentList().addAll(list);
        return list.size();
    }

    private void prepareBreakAssignments(ShiftSchedule schedule, String stage, LocalDate cycleStart) {
        var assignments = Optional.ofNullable(schedule.getAssignmentList()).orElse(List.of());
        var employees = Optional.ofNullable(schedule.getEmployeeList()).orElse(List.of());
        var weekly = Optional.ofNullable(schedule.getEmployeeWeeklyPreferenceList()).orElse(List.of());
        if (assignments.isEmpty() || employees.isEmpty()) return;

        // 日付集合
        Set<LocalDate> dates = assignments.stream().map(ShiftAssignmentPlanningEntity::getShiftDate).collect(java.util.stream.Collectors.toSet());

        // 週別可用インデックス
        Map<String, Map<Integer, io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference>> weeklyPrefByEmpDow = new HashMap<>();
        for (var p : weekly) {
            weeklyPrefByEmpDow.computeIfAbsent(p.getEmployeeCode(), k -> new HashMap<>())
                    .put(p.getDayOfWeek().intValue(), p);
        }

        int slotMinutes = appSettingService.getTimeResolutionMinutes();
        List<io.github.riemr.shift.optimization.entity.BreakAssignment> breakList = new java.util.ArrayList<>();
        for (var e : employees) {
            for (var d : dates) {
                var cand = buildBreakCandidates(weeklyPrefByEmpDow.get(e.getEmployeeCode()), d, slotMinutes);
                String id = e.getEmployeeCode() + ":" + d.toString();
                breakList.add(new io.github.riemr.shift.optimization.entity.BreakAssignment(id, e, d, cand));
            }
        }
        schedule.setBreakList(breakList);
    }

    private List<java.util.Date> buildBreakCandidates(Map<Integer, io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference> prefByDow,
                                                                LocalDate date, int slotMinutes) {
        List<java.util.Date> result = new java.util.ArrayList<>();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        if (prefByDow == null) return result;
        var pref = prefByDow.get(date.getDayOfWeek().getValue());
        if (pref == null || "OFF".equalsIgnoreCase(pref.getWorkStyle())) return result;
        if (pref.getBaseStartTime() == null || pref.getBaseEndTime() == null) return result;
        var start = pref.getBaseStartTime().toLocalTime();
        var end = pref.getBaseEndTime().toLocalTime();
        // 休憩60分が入る開始の最小・最大
        var latestStart = end.minusMinutes(60);
        if (!latestStart.isAfter(start)) return result;
        java.time.LocalTime t = start;
        while (!t.isAfter(latestStart)) {
            var dt = java.time.LocalDateTime.of(date, t);
            result.add(java.util.Date.from(dt.atZone(zone).toInstant()));
            t = t.plusMinutes(slotMinutes);
        }
        return result;
    }

    // ATTENDANCE: 有休/希望休/曜日OFFは候補から除外（休みをピン留め）
    // ASSIGNMENT: 当該スロットで出勤中の従業員のみを候補化
    private void prepareCandidateEmployees(ShiftSchedule schedule, String stage, LocalDate cycleStart) {
        if (schedule.getAssignmentList() == null || schedule.getEmployeeList() == null) return;

        final var employees = schedule.getEmployeeList();
        final var requests = Optional.ofNullable(schedule.getEmployeeRequestList()).orElse(List.of());
        final var weekly = Optional.ofNullable(schedule.getEmployeeWeeklyPreferenceList()).orElse(List.of());

        // インデックス化
        Map<String, Set<LocalDate>> offDatesByEmp = new HashMap<>();
        for (var r : requests) {
            if (r.getRequestKind() == null) continue;
            String kind = r.getRequestKind().toLowerCase();
            if ("off".equals(kind) || "paid_leave".equals(kind)) {
                LocalDate d = r.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                offDatesByEmp.computeIfAbsent(r.getEmployeeCode(), k -> new java.util.HashSet<>()).add(d);
            }
        }
        Map<String, Set<Integer>> weeklyOffByEmp = new HashMap<>();
        Map<String, Map<Integer, io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference>> weeklyPrefByEmpDow = new HashMap<>();
        for (var p : weekly) {
            weeklyPrefByEmpDow.computeIfAbsent(p.getEmployeeCode(), k -> new HashMap<>())
                    .put(p.getDayOfWeek().intValue(), p);
            if ("OFF".equalsIgnoreCase(p.getWorkStyle()))
                weeklyOffByEmp.computeIfAbsent(p.getEmployeeCode(), k -> new java.util.HashSet<>()).add(p.getDayOfWeek().intValue());
        }

        // 出勤ロスター（DBのshift_assignment）をロード（連勤上限チェックやASSIGNMENTのonDuty判定に使用）
        LocalDate cycleEnd = cycleStart.plusMonths(1);
        List<ShiftAssignment> attendance = List.of();
        {
            var list = shiftAssignmentMapper.selectByMonth(cycleStart, cycleEnd);
            if (schedule.getStoreCode() != null) {
                list = list.stream().filter(sa -> schedule.getStoreCode().equals(sa.getStoreCode())).toList();
            }
            attendance = list;
        }

        // 連勤上限（例: 最大6連勤まで許可）を候補生成で制御するためのカレンダを作成（既存ロスター基準）
        final int maxConsecutiveDays = 6; // 仕様に合わせて調整可
        Map<String, Set<LocalDate>> attendanceDaysByEmp = new HashMap<>();
        for (var sa : attendance) {
            if (sa.getEmployeeCode() == null || sa.getStartAt() == null) continue;
            LocalDate d = sa.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            attendanceDaysByEmp.computeIfAbsent(sa.getEmployeeCode(), k -> new java.util.HashSet<>()).add(d);
        }

        // パターンを社員別にインデックス
        Map<String, List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern>> patternByEmp =
                Optional.ofNullable(schedule.getEmployeeShiftPatternList()).orElse(List.of())
                        .stream().collect(java.util.stream.Collectors.groupingBy(io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern::getEmployeeCode));

        int dbgCount = 0;
        for (var a : schedule.getAssignmentList()) {
            LocalDate date = a.getShiftDate();
            List<io.github.riemr.shift.infrastructure.persistence.entity.Employee> cands;
            if ("ATTENDANCE".equals(stage)) {
                cands = employees.stream().filter(e -> {
                    var offSet = offDatesByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                    if (offSet.contains(date)) return false; // 有休/希望休
                    var offDow = weeklyOffByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                    if (offDow.contains(date.getDayOfWeek().getValue())) return false; // 曜日OFF
                    if (!withinWeeklyBase(weeklyPrefByEmpDow.get(e.getEmployeeCode()), date,
                            a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                            a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime())) return false;
                    if (!matchesAnyPattern(patternByEmp.get(e.getEmployeeCode()), date,
                            a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                            a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime())) return false;
                    // 連勤上限チェック：前6日が連続出勤で、この日を出勤にすると7連勤になる場合は候補から除外
                    var attDays = attendanceDaysByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                    if (wouldExceedConsecutiveCap(attDays, date, maxConsecutiveDays)) return false;
                    return true;
                }).toList();
            } else if ("ASSIGNMENT".equals(stage)) {
                // 当該スロット内に出勤が重なる従業員のみ（既存ロスター基準）
                var start = a.getStartAt().toInstant();
                var end = a.getEndAt().toInstant();
                Set<String> onDuty = new java.util.HashSet<>();
                for (var sa : attendance) {
                    if (sa.getStartAt() == null || sa.getEndAt() == null) continue;
                    var s = sa.getStartAt().toInstant();
                    var e = sa.getEndAt().toInstant();
                    boolean overlap = s.isBefore(end) && e.isAfter(start);
                    if (overlap) {
                        onDuty.add(sa.getEmployeeCode());
                    }
                }

                if (!onDuty.isEmpty()) {
                    // 既存ロスターに基づく通常のASSIGNMENT候補
                    cands = employees.stream().filter(e -> onDuty.contains(e.getEmployeeCode()))
                            .filter(e -> withinWeeklyBase(weeklyPrefByEmpDow.get(e.getEmployeeCode()), date,
                                    a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                                    a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime()))
                            .filter(e -> matchesAnyPattern(patternByEmp.get(e.getEmployeeCode()), date,
                                    a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                                    a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime()))
                            // 連勤上限チェック（ASSIGNMENTでも、既存ロスター基準で7連勤を防ぐ）
                            .filter(e -> !wouldExceedConsecutiveCap(attendanceDaysByEmp.getOrDefault(e.getEmployeeCode(), Set.of()), date, maxConsecutiveDays))
                            .toList();
                } else {
                    // フォールバック: 既存ロスターが空（未生成）の場合は週次設定ベースで可用判定
                    // これにより、事前にATTENDANCEを走らせなくてもASSIGNMENT単体で割当可能になる
                    cands = employees.stream()
                            .filter(e -> {
                                var offSet = offDatesByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                                if (offSet.contains(date)) return false; // 有休/希望休
                                var offDow = weeklyOffByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                                if (offDow.contains(date.getDayOfWeek().getValue())) return false; // 曜日OFF
                                if (!withinWeeklyBase(weeklyPrefByEmpDow.get(e.getEmployeeCode()), date,
                                        a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                                        a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime())) return false;
                                if (!matchesAnyPattern(patternByEmp.get(e.getEmployeeCode()), date,
                                        a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime(),
                                        a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime())) return false;
                                // 連勤上限（既存ロスターが無い場合は事実上無視されるが、念のため空集合で判定）
                                var attDays = attendanceDaysByEmp.getOrDefault(e.getEmployeeCode(), Set.of());
                                if (wouldExceedConsecutiveCap(attDays, date, maxConsecutiveDays)) return false;
                                return true;
                            })
                            .toList();
                    if (log.isInfoEnabled()) {
                        log.info("ASSIGNMENT fallback: No on-duty roster for {}. Weekly-base candidates={}", date, cands.size());
                    }
                }
            } else {
                cands = employees; // デフォルト（フェールセーフ）
            }
            a.setCandidateEmployees(cands);

            // Debug: 出力抑制のため先頭数件だけ候補数を記録
            if (dbgCount < 10 && log.isDebugEnabled()) {
                var st = a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
                var et = a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
                log.debug("CandDbg date={} time={}~{} kind={} candCount={}", date, st, et, a.getWorkKind(), (cands == null ? 0 : cands.size()));
                dbgCount++;
            }
            if ((cands == null || cands.isEmpty()) && log.isWarnEnabled()) {
                var st = a.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
                var et = a.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
                log.warn("No candidates for slot date={} time={}~{} kind={} store={} dept={}", date, st, et, a.getWorkKind(), schedule.getStoreCode(), schedule.getDepartmentCode());
            }
        }

        // ATTENDANCE: 出勤固定（MANDATORY）の人は、その日に少なくとも1スロットは必ず候補を単一化して“ピン”に近い状態にする
        if ("ATTENDANCE".equals(stage)) {
            // 週MANDATORYをインデックス化（OFFや有休が優先されるため、該当日は除外）
            Map<LocalDate, List<String>> mandatoryByDate = new HashMap<>();
            for (var p : weekly) {
                if (!"MANDATORY".equalsIgnoreCase(p.getWorkStyle())) continue;
                String emp = p.getEmployeeCode();
                // 対象月全日に対して、該当DOWを付与（簡便実装）
                LocalDate d = cycleStart;
                LocalDate end = cycleStart.plusMonths(1);
                while (d.isBefore(end)) {
                    if (d.getDayOfWeek().getValue() == p.getDayOfWeek().intValue()) {
                        // OFF/有休は優先：この日はMANDATORYから除外
                        var offSet = offDatesByEmp.getOrDefault(emp, Set.of());
                        var offDow = weeklyOffByEmp.getOrDefault(emp, Set.of());
                        if (!offSet.contains(d) && !offDow.contains(d.getDayOfWeek().getValue())) {
                            mandatoryByDate.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(emp);
                        }
                    }
                    d = d.plusDays(1);
                }
            }

            // 日付ごとに未予約スロットへ順に単一候補化（過度な拘束を避けるため各MANDATORYあたり1スロット）
            Map<LocalDate, List<ShiftAssignmentPlanningEntity>> byDate = new HashMap<>();
            for (var a : schedule.getAssignmentList()) {
                byDate.computeIfAbsent(a.getShiftDate(), k -> new java.util.ArrayList<>()).add(a);
            }
            for (var entry : mandatoryByDate.entrySet()) {
                LocalDate date = entry.getKey();
                var emps = entry.getValue();
                var slots = byDate.getOrDefault(date, List.of());
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

    private boolean matchesAnyPattern(List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern> list,
                                      LocalDate date, java.time.LocalTime slotStart, java.time.LocalTime slotEnd) {
        // シフトパターンが設定されていない場合は制限なしとして true を返す
        if (list == null || list.isEmpty()) return true;
        for (var p : list) {
            if (Boolean.FALSE.equals(p.getActive())) continue;
            var ps = p.getStartTime().toLocalTime();
            var pe = p.getEndTime().toLocalTime();
            if ((slotStart.equals(ps) || slotStart.isAfter(ps)) && (slotEnd.isBefore(pe) || slotEnd.equals(pe))) {
                return true;
            }
        }
        return false;
    }

    private boolean withinWeeklyBase(Map<Integer, io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference> prefByDow,
                                     LocalDate date, java.time.LocalTime slotStart, java.time.LocalTime slotEnd) {
        if (prefByDow == null) return true;
        var pref = prefByDow.get(date.getDayOfWeek().getValue());
        if (pref == null) return true;
        if ("OFF".equalsIgnoreCase(pref.getWorkStyle())) return false;
        if (pref.getBaseStartTime() == null || pref.getBaseEndTime() == null) return true;
        var bs = pref.getBaseStartTime().toLocalTime();
        var be = pref.getBaseEndTime().toLocalTime();
        return (slotStart.equals(bs) || slotStart.isAfter(bs)) && (slotEnd.isBefore(be) || slotEnd.equals(be));
    }

    // 連勤上限（cap）を超えるか（この日を出勤にすると cap+1 連勤になるか）を、既存ロスターの出勤日だけで判定
    private boolean wouldExceedConsecutiveCap(Set<LocalDate> attendanceDays, LocalDate date, int cap) {
        if (attendanceDays == null || attendanceDays.isEmpty()) return false;
        // 直近 cap 日がすべて出勤なら、この日を出勤にすると cap+1 連勤になる
        for (int i = 1; i <= cap; i++) {
            LocalDate d = date.minusDays(i);
            if (!attendanceDays.contains(d)) {
                return false; // 途中に非出勤日があるため、7連勤にはならない
            }
        }
        return true;
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
        // ハード制約違反チェック（保存ブロック）
        if (best.getScore() != null && best.getScore().hardScore() < 0) {
            log.error("🚨 HARD CONSTRAINT VIOLATION DETECTED! Score: {}", best.getScore());
            log.error("🚫 Database save BLOCKED due to constraint violations");
            log.error("📋 Please review and fix the following:");
            
            // 制約違反の詳細分析と改善提案を出力
            analyzeConstraintViolations(best);
            // ハード制約違反がある場合は既存データの削除や保存を行わない
            return;
        }
        
        // 問題データの状況をログ出力
        if (best.getAssignmentList() == null || best.getAssignmentList().isEmpty()) {
            log.error("❌ No assignment list data to persist! AssignmentList is empty.");
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

    // ATTENDANCE 用など、ソリューション型に依存せずスコアのみでフェーズ更新
    private void updatePhaseScore(ProblemKey key, HardSoftScore score) {
        if (score == null) return;
        String phase = (score.initScore() < 0) ? "初期解生成中" : "最適化中";
        currentPhaseMap.put(key, phase);
        log.debug("Phase update(score) for {}: {} - Score: {}", key, phase, score);
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
