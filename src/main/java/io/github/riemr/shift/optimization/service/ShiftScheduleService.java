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
import io.github.riemr.shift.application.service.AppSettingService;
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
    private final AppSettingService appSettingService;

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
        ProblemKey key = new ProblemKey(java.time.YearMonth.from(month), storeCode, month);

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
        // jobMapから該当するProblemKeyを検索
        ProblemKey targetKey = null;
        for (ProblemKey key : jobMap.keySet()) {
            if (key.getStoreCode().equals(storeCode) && 
                toProblemId(key.getCycleStart()) == problemId) {
                targetKey = key;
                break;
            }
        }
        
        if (targetKey == null) {
            return new SolveStatusDto("UNKNOWN", 0, 0, "未開始");
        }
        
        SolverStatus status = solverManager.getSolverStatus(targetKey);
        Instant began = startMap.get(targetKey);
        if (began == null) return new SolveStatusDto("UNKNOWN", 0, 0, "未開始");

        long now = Instant.now().toEpochMilli();
        long finish = began.plus(spentLimit).toEpochMilli();
        
        // 時間ベースの進捗計算
        int pct = (int) Math.min(100, ((now - began.toEpochMilli()) * 100) / (finish - began.toEpochMilli()));
        
        // フェーズ情報を取得
        String currentPhase = currentPhaseMap.getOrDefault(targetKey, "初期化中");
        
        if (status == SolverStatus.NOT_SOLVING) {
            pct = 100;
            currentPhase = "完了";
            
            // ハード制約違反チェック
            SolverJob<ShiftSchedule, ProblemKey> job = jobMap.get(targetKey);
            if (job != null) {
                try {
                    ShiftSchedule finalSolution = job.getFinalBestSolution();
                    if (finalSolution.getScore() != null && finalSolution.getScore().hardScore() < 0) {
                        // ハード制約違反がある場合は違反情報を含めて返す
                        List<String> violationMessages = analyzeConstraintViolationsForUI(finalSolution);
                        // 後始末
                        jobMap.remove(targetKey);
                        startMap.remove(targetKey);
                        currentPhaseMap.remove(targetKey);
                        return SolveStatusDto.withConstraintViolations(status.name(), pct, finish, "制約違反あり", violationMessages);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.error("Failed to get final solution", e);
                }
            }
            
            // 後始末
            jobMap.remove(targetKey);
            startMap.remove(targetKey);
            currentPhaseMap.remove(targetKey);
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
        LocalDate from = computeCycleStart(anyDayInMonth);
        LocalDate to   = from.plusMonths(1);  // 半開区間

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
        LocalDate from = computeCycleStart(anyDayInMonth);
        LocalDate to   = from.plusMonths(1);  // 半開区間

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
        ShiftSchedule unsolved = repository.fetchShiftSchedule(cycleStart, key.getStoreCode());
        unsolved.setEmployeeRegisterSkillList(employeeRegisterSkillMapper.selectByExample(null));
        // Repository 側で必要なフィールドをセット済みだが、問題 ID だけはここで上書きしておく
        unsolved.setProblemId(toProblemId(cycleStart));
        if (unsolved.getAssignmentList() == null) {
            unsolved.setAssignmentList(new ArrayList<>());
        }
        
        // 実行可能性チェック：全員希望休の日があるかチェック
        validateProblemFeasibility(unsolved);
        
        log.info("Loaded unsolved problem for {} store {} ({} assignments)", cycleStart, unsolved.getStoreCode(), unsolved.getAssignmentList().size());
        return unsolved;
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
    private void persistResult(ShiftSchedule best) {
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
