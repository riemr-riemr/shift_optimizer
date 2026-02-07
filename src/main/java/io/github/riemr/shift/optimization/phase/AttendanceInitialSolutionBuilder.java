package io.github.riemr.shift.optimization.phase;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlySetting;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import io.github.riemr.shift.optimization.solution.AttendanceSolution;
import io.github.riemr.shift.util.EmployeeRequestKinds;
import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.phase.custom.CustomPhaseCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ATTENDANCE フェーズのカスタム初期解生成
 * 
 * ロジック：
 * 1. 従業員の曜日別働き方を考慮して、働ける日にランダムに出勤を入れる
 * 2. 勤務時間が週別・月別の勤務時間制限に収まる範囲でランダム出勤
 * 3. 出勤時のシフトパターンは優先度最高(priority>=2)からランダム選択
 */
public class AttendanceInitialSolutionBuilder implements CustomPhaseCommand<AttendanceSolution> {
    
    private static final Logger log = LoggerFactory.getLogger(AttendanceInitialSolutionBuilder.class);
    private final Random random = new Random();
    
    @Override
    public void changeWorkingSolution(ScoreDirector<AttendanceSolution> scoreDirector) {
        log.error("=== CUSTOM PHASE STARTED ==="); // デバッグ用
        AttendanceSolution solution = scoreDirector.getWorkingSolution();
        
        if (solution.getPatternAssignments() == null || solution.getPatternAssignments().isEmpty()) {
            log.warn("No pattern assignments to initialize");
            return;
        }
        
        log.info("Starting custom initial solution generation for {} patterns", 
                solution.getPatternAssignments().size());
        
        // 従業員ごとのデータ構造を構築
        
        Map<String, List<EmployeeShiftPattern>> patternsByEmployee = solution.getEmployeeShiftPatternList().stream()
                .filter(p -> !Boolean.FALSE.equals(p.getActive()) && 
                           p.getPriority() != null && p.getPriority() >= 2)
                .collect(Collectors.groupingBy(EmployeeShiftPattern::getEmployeeCode));
        
        Map<String, List<EmployeeWeeklyPreference>> weeklyPrefsByEmployee = solution.getEmployeeWeeklyPreferenceList().stream()
                .collect(Collectors.groupingBy(EmployeeWeeklyPreference::getEmployeeCode));
        
        Map<String, EmployeeMonthlySetting> monthlySettingsByEmployee = solution.getEmployeeMonthlySettingList().stream()
                .collect(Collectors.toMap(EmployeeMonthlySetting::getEmployeeCode, s -> s, (a, b) -> a));
        
        // 日付別・従業員別のパターンをグループ化
        Map<LocalDate, Map<String, List<DailyPatternAssignmentEntity>>> patternsByDateAndEmployee = 
                solution.getPatternAssignments().stream()
                .collect(Collectors.groupingBy(
                    DailyPatternAssignmentEntity::getDate,
                    Collectors.groupingBy(p -> getEligibleEmployeeCodesString(p))
                ));
        
        // 従業員別の労働時間を追跡
        Map<String, Map<YearMonth, Integer>> monthlyMinutesByEmployee = new HashMap<>();
        Map<String, Map<LocalDate, Integer>> weeklyMinutesByEmployee = new HashMap<>();

        // 希望（employee_request）を初期解に反映し、変更不可にする
        applyEmployeeRequests(scoreDirector, solution, monthlyMinutesByEmployee, weeklyMinutesByEmployee);
        
        // 従業員ごとに初期解を生成
        int processedEmployees = 0;
        int skippedEmployees = 0;
        
        for (Employee employee : solution.getEmployeeList()) {
            String empCode = employee.getEmployeeCode();
            
            List<EmployeeShiftPattern> empPatterns = patternsByEmployee.getOrDefault(empCode, Collections.emptyList());
            if (empPatterns.isEmpty()) {
                skippedEmployees++;
                log.debug("Employee {} has no shift patterns (priority>=2), skipped", empCode);
                continue; // シフトパターンがない従業員はスキップ
            }
            
            List<EmployeeWeeklyPreference> weeklyPrefs = weeklyPrefsByEmployee.getOrDefault(empCode, Collections.emptyList());
            EmployeeMonthlySetting monthlySetting = monthlySettingsByEmployee.get(empCode);
            
            generateInitialAssignments(scoreDirector, employee, empPatterns, weeklyPrefs, monthlySetting,
                    patternsByDateAndEmployee, monthlyMinutesByEmployee, weeklyMinutesByEmployee);
            processedEmployees++;
        }
        
        log.info("Employee processing: {} processed, {} skipped (no patterns)", processedEmployees, skippedEmployees);
        
        int assignedCount = (int) solution.getPatternAssignments().stream()
                .filter(p -> p.getAssignedEmployee() != null).count();
        
        log.info("Initial solution generated: {}/{} patterns assigned", 
                assignedCount, solution.getPatternAssignments().size());
        
        // スコアを強制計算して初期値を記録
        if (assignedCount > 0) {
            scoreDirector.triggerVariableListeners();
            var currentScore = scoreDirector.getWorkingSolution().getScore();
            log.info("CUSTOM PHASE FINAL SCORE: {}", currentScore);
        }
    }
    
    private void generateInitialAssignments(
            ScoreDirector<AttendanceSolution> scoreDirector,
            Employee employee,
            List<EmployeeShiftPattern> empPatterns,
            List<EmployeeWeeklyPreference> weeklyPrefs,
            EmployeeMonthlySetting monthlySetting,
            Map<LocalDate, Map<String, List<DailyPatternAssignmentEntity>>> patternsByDateAndEmployee,
            Map<String, Map<YearMonth, Integer>> monthlyMinutesByEmployee,
            Map<String, Map<LocalDate, Integer>> weeklyMinutesByEmployee) {
        
        String empCode = employee.getEmployeeCode();
        int assignmentsForThisEmployee = 0;
        
        // 週次設定のマップ化
        Map<DayOfWeek, EmployeeWeeklyPreference> weeklyPrefMap = weeklyPrefs.stream()
                .filter(p -> p.getDayOfWeek() != null)
                .collect(Collectors.toMap(
                    p -> DayOfWeek.of(p.getDayOfWeek()),
                    p -> p,
                    (a, b) -> a));
        
        // 各日付を処理
        for (Map.Entry<LocalDate, Map<String, List<DailyPatternAssignmentEntity>>> dateEntry : patternsByDateAndEmployee.entrySet()) {
            LocalDate date = dateEntry.getKey();
            DayOfWeek dayOfWeek = date.getDayOfWeek();
            
            // この従業員が働ける日かチェック
            EmployeeWeeklyPreference dayPref = weeklyPrefMap.get(dayOfWeek);
            if (dayPref != null && "OFF".equalsIgnoreCase(dayPref.getWorkStyle())) {
                continue; // 週次OFFの日はスキップ
            }
            
            // この従業員が対象のパターンを取得
            List<DailyPatternAssignmentEntity> availablePatterns = new ArrayList<>();
            for (List<DailyPatternAssignmentEntity> patterns : dateEntry.getValue().values()) {
                for (DailyPatternAssignmentEntity pattern : patterns) {
                    if (pattern.isPinned()) continue;
                    if (pattern.getEligibleEmployees().stream()
                            .anyMatch(e -> empCode.equals(e.getEmployeeCode()))) {
                        availablePatterns.add(pattern);
                    }
                }
            }
            
            if (availablePatterns.isEmpty()) {
                continue;
            }
            
            // ランダムに出勤するかどうか決定（70%の確率で出勤候補とする）
            double randomValue = random.nextDouble();
            if (randomValue > 0.7) {
                continue;
            }
            
            // 利用可能なシフトパターンから選択
            List<EmployeeShiftPattern> dayPatterns = empPatterns.stream()
                    .filter(p -> availablePatterns.stream().anyMatch(ap -> 
                        ap.getPatternStart().equals(p.getStartTime().toLocalTime()) &&
                        ap.getPatternEnd().equals(p.getEndTime().toLocalTime())))
                    .collect(Collectors.toList());
            
            if (dayPatterns.isEmpty()) {
                continue;
            }
            
            // 優先度最高のパターンから選択
            int maxPriority = dayPatterns.stream().mapToInt(p -> p.getPriority()).max().orElse(2);
            List<EmployeeShiftPattern> highPriorityPatterns = dayPatterns.stream()
                    .filter(p -> p.getPriority() == maxPriority)
                    .collect(Collectors.toList());
            
            EmployeeShiftPattern selectedPattern = highPriorityPatterns.get(random.nextInt(highPriorityPatterns.size()));
            
            // 対応するDailyPatternAssignmentEntityを検索
            Optional<DailyPatternAssignmentEntity> targetPattern = availablePatterns.stream()
                    .filter(ap -> ap.getPatternStart().equals(selectedPattern.getStartTime().toLocalTime()) &&
                                 ap.getPatternEnd().equals(selectedPattern.getEndTime().toLocalTime()) &&
                                 ap.getAssignedEmployee() == null &&
                                 !ap.isPinned())
                    .findFirst();
            
            if (targetPattern.isPresent()) {
                DailyPatternAssignmentEntity pattern = targetPattern.get();
                
                // 労働時間制限チェック
                int patternMinutes = calculatePatternMinutes(pattern);
                
                if (isWithinWorkingHourLimits(employee, monthlySetting, date, patternMinutes,
                        monthlyMinutesByEmployee, weeklyMinutesByEmployee)) {
                    
                    // 割当実行
                    scoreDirector.beforeVariableChanged(pattern, "assignedEmployee");
                    pattern.setAssignedEmployee(employee);
                    scoreDirector.afterVariableChanged(pattern, "assignedEmployee");
                    
                    // 労働時間を更新
                    updateWorkingHours(empCode, date, patternMinutes, monthlyMinutesByEmployee, weeklyMinutesByEmployee);
                    assignmentsForThisEmployee++;
                }
            }
        }
        
        if (assignmentsForThisEmployee == 0) {
            log.debug("Employee {} got 0 assignments (patterns: {}, weekly prefs: {})", 
                    empCode, empPatterns.size(), weeklyPrefs.size());
        } else {
            log.debug("Employee {} got {} assignments", empCode, assignmentsForThisEmployee);
        }
    }
    
    private boolean isWithinWorkingHourLimits(
            Employee employee,
            EmployeeMonthlySetting monthlySetting,
            LocalDate date,
            int additionalMinutes,
            Map<String, Map<YearMonth, Integer>> monthlyMinutesByEmployee,
            Map<String, Map<LocalDate, Integer>> weeklyMinutesByEmployee) {
        
        String empCode = employee.getEmployeeCode();
        
        // 週次制限チェック
        LocalDate weekStart = getWeekStart(date);
        int currentWeeklyMinutes = weeklyMinutesByEmployee
                .getOrDefault(empCode, Collections.emptyMap())
                .getOrDefault(weekStart, 0);
        
        if (employee.getMaxWorkHoursWeek() != null) {
            int maxWeeklyMinutes = employee.getMaxWorkHoursWeek() * 60;
            if (currentWeeklyMinutes + additionalMinutes > maxWeeklyMinutes) {
                return false;
            }
        }
        
        // 月次制限チェック
        if (monthlySetting != null && monthlySetting.getMaxWorkHours() != null) {
            YearMonth month = YearMonth.from(date);
            int currentMonthlyMinutes = monthlyMinutesByEmployee
                    .getOrDefault(empCode, Collections.emptyMap())
                    .getOrDefault(month, 0);
            
            int maxMonthlyMinutes = monthlySetting.getMaxWorkHours() * 60;
            if (currentMonthlyMinutes + additionalMinutes > maxMonthlyMinutes) {
                return false;
            }
        }
        
        return true;
    }
    
    private void updateWorkingHours(
            String empCode,
            LocalDate date,
            int minutes,
            Map<String, Map<YearMonth, Integer>> monthlyMinutesByEmployee,
            Map<String, Map<LocalDate, Integer>> weeklyMinutesByEmployee) {
        
        // 週次更新
        LocalDate weekStart = getWeekStart(date);
        weeklyMinutesByEmployee.computeIfAbsent(empCode, k -> new HashMap<>())
                .merge(weekStart, minutes, Integer::sum);
        
        // 月次更新
        YearMonth month = YearMonth.from(date);
        monthlyMinutesByEmployee.computeIfAbsent(empCode, k -> new HashMap<>())
                .merge(month, minutes, Integer::sum);
    }
    
    private LocalDate getWeekStart(LocalDate date) {
        return date.with(WeekFields.ISO.getFirstDayOfWeek());
    }
    
    private int calculatePatternMinutes(DailyPatternAssignmentEntity pattern) {
        return (int) java.time.Duration.between(pattern.getPatternStart(), pattern.getPatternEnd()).toMinutes();
    }

    private void applyEmployeeRequests(ScoreDirector<AttendanceSolution> scoreDirector,
                                       AttendanceSolution solution,
                                       Map<String, Map<YearMonth, Integer>> monthlyMinutesByEmployee,
                                       Map<String, Map<LocalDate, Integer>> weeklyMinutesByEmployee) {
        List<EmployeeRequest> requests = Optional.ofNullable(solution.getEmployeeRequestList()).orElse(List.of());
        if (requests.isEmpty()) return;

        Map<LocalDate, Map<String, List<DailyPatternAssignmentEntity>>> patternsByDateAndTime =
                solution.getPatternAssignments().stream()
                        .collect(Collectors.groupingBy(
                                DailyPatternAssignmentEntity::getDate,
                                Collectors.groupingBy(p -> p.getPatternStart() + "_" + p.getPatternEnd())
                        ));

        for (EmployeeRequest request : requests) {
            if (!isPreferOnRequest(request)) continue;
            String empCode = request.getEmployeeCode();
            LocalDate date = toLocalDateSafe(request.getRequestDate());
            LocalTime from = toLocalTimeSafe(request.getFromTime());
            LocalTime to = toLocalTimeSafe(request.getToTime());
            if (empCode == null || date == null || from == null || to == null) continue;

            String key = from + "_" + to;
            List<DailyPatternAssignmentEntity> candidates = patternsByDateAndTime
                    .getOrDefault(date, Map.of())
                    .getOrDefault(key, List.of());
            if (candidates.isEmpty()) {
                log.warn("Prefer-on request has no matching pattern: emp={}, date={}, time={}-{}",
                        empCode, date, from, to);
                continue;
            }
            DailyPatternAssignmentEntity target = candidates.stream()
                    .filter(p -> p.getEligibleEmployees().stream().anyMatch(e -> empCode.equals(e.getEmployeeCode())))
                    .filter(p -> p.getAssignedEmployee() == null
                            || empCode.equals(p.getAssignedEmployee().getEmployeeCode()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                log.warn("Prefer-on request has no eligible slot: emp={}, date={}, time={}-{}",
                        empCode, date, from, to);
                continue;
            }

            var employee = solution.getEmployeeList().stream()
                    .filter(e -> empCode.equals(e.getEmployeeCode()))
                    .findFirst()
                    .orElse(null);
            if (employee == null) {
                log.warn("Prefer-on request employee not found in solution: emp={}, date={}, time={}-{}",
                        empCode, date, from, to);
                continue;
            }
            if (target.getAssignedEmployee() == null || !empCode.equals(target.getAssignedEmployee().getEmployeeCode())) {
                scoreDirector.beforeVariableChanged(target, "assignedEmployee");
                target.setAssignedEmployee(employee);
                scoreDirector.afterVariableChanged(target, "assignedEmployee");
            }
            target.setPinned(true);

            int minutes = calculatePatternMinutes(target);
            updateWorkingHours(empCode, date, minutes, monthlyMinutesByEmployee, weeklyMinutesByEmployee);
        }
    }

    private boolean isPreferOnRequest(EmployeeRequest request) {
        if (request == null || request.getRequestKind() == null) return false;
        return EmployeeRequestKinds.PREFER_ON.equalsIgnoreCase(request.getRequestKind().trim());
    }

    private LocalDate toLocalDateSafe(java.util.Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Date) return ((java.sql.Date) date).toLocalDate();
        return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }

    private LocalTime toLocalTimeSafe(java.util.Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Time) return ((java.sql.Time) date).toLocalTime();
        return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime();
    }
    
    private String getEligibleEmployeeCodesString(DailyPatternAssignmentEntity pattern) {
        return pattern.getEligibleEmployees().stream()
                .map(Employee::getEmployeeCode)
                .sorted()
                .collect(Collectors.joining(","));
    }
}
