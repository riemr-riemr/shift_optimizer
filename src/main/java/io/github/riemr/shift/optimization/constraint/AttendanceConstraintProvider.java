package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

import java.time.LocalTime;
import java.time.ZoneId;

public class AttendanceConstraintProvider implements ConstraintProvider {
    @Override
    public Constraint[] defineConstraints(ConstraintFactory f) {
        return new Constraint[] {
                forbidRequestedDayOff(f),
                forbidWeeklyOffOrOutsideBase(f),
                employeeNotDoubleBooked(f),
                // requirePatternAlignment(f), // eligibleEmployeesで事前フィルタするため不要
                // headcountBalance(f), // テスト的にコメントアウト
                headcountShortageWhenNone(f),
                weeklyWorkHoursRange(f),
                monthlyWorkHoursRange(f),
                consecutiveSevenDaysPenalty(f),
                overstaffLightPenalty(f)
        };
    }

    // 同一従業員・同一日でパターン時間帯が重なる割当を禁止（ATTENDANCEのハード制約）
    private Constraint employeeNotDoubleBooked(ConstraintFactory f) {
        return f.forEachUniquePair(DailyPatternAssignmentEntity.class,
                        Joiners.equal(e -> e.getAssignedEmployee() != null ? e.getAssignedEmployee().getEmployeeCode() : null,
                                e -> e.getAssignedEmployee() != null ? e.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.equal(DailyPatternAssignmentEntity::getDate))
                .filter((a, b) -> overlaps(a, b))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Attendance double booking");
    }

    private static boolean overlaps(DailyPatternAssignmentEntity a, DailyPatternAssignmentEntity b) {
        if (a.getAssignedEmployee() == null || b.getAssignedEmployee() == null) return false;
        if (!a.getDate().equals(b.getDate())) return false;
        // [start, end) の重なり
        return a.getPatternStart().isBefore(b.getPatternEnd()) && b.getPatternStart().isBefore(a.getPatternEnd());
    }

    private Constraint forbidRequestedDayOff(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(e -> e.getAssignedEmployee() != null)
                .join(EmployeeRequest.class,
                        Joiners.equal(e -> e.getAssignedEmployee().getEmployeeCode(), EmployeeRequest::getEmployeeCode),
                        Joiners.filtering((e, r) -> "off".equalsIgnoreCase(r.getRequestKind())
                                && toLocalDateSafe(r.getRequestDate()).equals(e.getDate())))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Requested day off");
    }

    private Constraint forbidWeeklyOffOrOutsideBase(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(e -> e.getAssignedEmployee() != null)
                .join(EmployeeWeeklyPreference.class,
                        Joiners.equal(e -> e.getAssignedEmployee().getEmployeeCode(), EmployeeWeeklyPreference::getEmployeeCode),
                        Joiners.filtering((e, p) -> p.getDayOfWeek() != null && p.getDayOfWeek().intValue() == e.getDate().getDayOfWeek().getValue()))
                .filter((e, p) -> {
                    if ("OFF".equalsIgnoreCase(p.getWorkStyle())) return true;
                    if (p.getBaseStartTime() == null || p.getBaseEndTime() == null) return false;
                    LocalTime bs = p.getBaseStartTime().toLocalTime();
                    LocalTime be = p.getBaseEndTime().toLocalTime();
                    return e.getPatternStart().isBefore(bs) || e.getPatternEnd().isAfter(be);
                })
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Weekly off / outside base");
    }

    private Constraint requirePatternAlignment(ConstraintFactory f) {
        // パターン整合性をソフト制約に変更（優先度を下げて実行可能解を確保）
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(e -> e.getAssignedEmployee() != null)
                .ifNotExists(EmployeeShiftPattern.class,
                        Joiners.equal(e -> e.getAssignedEmployee().getEmployeeCode(), EmployeeShiftPattern::getEmployeeCode),
                        Joiners.filtering((e, p) -> !Boolean.FALSE.equals(p.getActive())
                                && p.getPriority() != null && p.getPriority().intValue() >= 2
                                && p.getStartTime().toLocalTime().equals(e.getPatternStart())
                                && p.getEndTime().toLocalTime().equals(e.getPatternEnd())))
                .penalize(HardSoftScore.ofSoft(5000))  // ハード→ソフト制約に変更
                .asConstraint("Pattern alignment preferred");
    }

    private Constraint headcountBalance(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .join(DailyPatternAssignmentEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getStoreCode, e -> e.getStoreCode()),
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, e -> java.sql.Date.valueOf(e.getDate())),
                        Joiners.filtering((d, e) -> e.getAssignedEmployee() != null
                                && timeWithin(e, d.getSlotTime())))
                .groupBy((d, e) -> d, org.optaplanner.core.api.score.stream.ConstraintCollectors.countBi())
                .penalize(HardSoftScore.ofSoft(100), (d, assigned) -> Math.abs(d.getRequiredUnits() - assigned))
                .asConstraint("Quarter headcount balance");
    }

    // 需要スロットに対する超過（割当人数 > requiredUnits）に軽いペナルティを課す
    private Constraint overstaffLightPenalty(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .join(DailyPatternAssignmentEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getStoreCode, e -> e.getStoreCode()),
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, e -> java.sql.Date.valueOf(e.getDate())),
                        Joiners.filtering((d, e) -> e.getAssignedEmployee() != null && timeWithin(e, d.getSlotTime())))
                .groupBy((d, e) -> d, org.optaplanner.core.api.score.stream.ConstraintCollectors.countBi())
                .filter((d, assigned) -> assigned > (d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits())))
                .penalize(HardSoftScore.ofSoft(10), (d, assigned) -> assigned - (d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits())))
                .asConstraint("Attendance: overstaff light penalty");
    }

    // 需要スロットに対して誰も割り当てが無い場合のショートペナルティ（不足分を強く誘導）
    private Constraint headcountShortageWhenNone(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .ifNotExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getStoreCode, e -> e.getStoreCode()),
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, e -> java.sql.Date.valueOf(e.getDate())),
                        Joiners.filtering((d, e) -> e.getAssignedEmployee() != null && timeWithin(e, d.getSlotTime())))
                .penalize(HardSoftScore.ofSoft(50), RegisterDemandQuarter::getRequiredUnits)
                .asConstraint("Quarter headcount shortage (none)");
    }

    private static boolean timeWithin(DailyPatternAssignmentEntity e, LocalTime slot) {
        return (slot.equals(e.getPatternStart()) || slot.isAfter(e.getPatternStart()))
                && slot.isBefore(e.getPatternEnd());
    }

    // ===== 労働時間制約（ATTENDANCE） =====
    
    /**
     * パターン割り当ての労働分数を計算
     */
    private static int calculatePatternMinutes(DailyPatternAssignmentEntity pattern) {
        return (int) java.time.Duration.between(pattern.getPatternStart(), pattern.getPatternEnd()).toMinutes();
    }
    
    /**
     * 指定日付の週開始日を取得（月曜日起点）
     */
    private static java.time.LocalDate getWeekStart(java.time.LocalDate date) {
        int dayOfWeek = date.getDayOfWeek().getValue(); // 1=月曜日
        return date.minusDays(dayOfWeek - 1);
    }
    
    /**
     * 週次労働時間制約（ソフト制約）
     */
    private Constraint weeklyWorkHoursRange(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(pattern -> pattern.getAssignedEmployee() != null)
                .groupBy(DailyPatternAssignmentEntity::getAssignedEmployee,
                        pattern -> getWeekStart(pattern.getDate()),
                        org.optaplanner.core.api.score.stream.ConstraintCollectors.toList())
                .filter((emp, weekStart, patterns) -> {
                    int totalMinutes = patterns.stream()
                            .mapToInt(AttendanceConstraintProvider::calculatePatternMinutes)
                            .sum();
                    // 月をまたぐ週は最小制約を無視
                    boolean crossesMonth = !java.time.YearMonth.from(weekStart)
                            .equals(java.time.YearMonth.from(weekStart.plusDays(6)));
                    boolean belowMin = !crossesMonth && emp.getMinWorkHoursWeek() != null 
                            && totalMinutes < emp.getMinWorkHoursWeek() * 60;
                    boolean aboveMax = emp.getMaxWorkHoursWeek() != null 
                            && totalMinutes > emp.getMaxWorkHoursWeek() * 60;
                    return belowMin || aboveMax;
                })
                .penalize(HardSoftScore.ofSoft(200), (emp, weekStart, patterns) -> {
                    int totalMinutes = patterns.stream()
                            .mapToInt(AttendanceConstraintProvider::calculatePatternMinutes)
                            .sum();
                    int penalty = 0;
                    // 月をまたぐ週は最小制約を無視
                    boolean crossesMonth = !java.time.YearMonth.from(weekStart)
                            .equals(java.time.YearMonth.from(weekStart.plusDays(6)));
                    if (!crossesMonth && emp.getMinWorkHoursWeek() != null 
                            && totalMinutes < emp.getMinWorkHoursWeek() * 60) {
                        // 最小時間制約（最高優先度）
                        penalty += (emp.getMinWorkHoursWeek() * 60 - totalMinutes) * 1000;
                    }
                    if (emp.getMaxWorkHoursWeek() != null && totalMinutes > emp.getMaxWorkHoursWeek() * 60) {
                        // 最大時間制約（2番目優先度）
                        penalty += (totalMinutes - emp.getMaxWorkHoursWeek() * 60) * 500;
                    }
                    return Math.max(penalty, 1);
                })
                .asConstraint("Attendance: weekly work hours range");
    }
    
    /**
     * 月次労働時間制約（ソフト制約）
     */
    private Constraint monthlyWorkHoursRange(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(pattern -> pattern.getAssignedEmployee() != null)
                .join(io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlySetting.class,
                        Joiners.equal(p -> p.getAssignedEmployee().getEmployeeCode(),
                                     io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlySetting::getEmployeeCode),
                        Joiners.filtering((p, setting) -> 
                            java.time.YearMonth.from(p.getDate()).equals(
                                java.time.YearMonth.from(setting.getMonthStart()
                                    .toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()))))
                .groupBy((pattern, setting) -> setting,
                        org.optaplanner.core.api.score.stream.ConstraintCollectors.sum((pattern, setting) -> 
                            calculatePatternMinutes(pattern)))
                .filter((setting, totalMinutes) -> {
                    boolean belowMin = setting.getMinWorkHours() != null 
                            && totalMinutes < setting.getMinWorkHours() * 60;
                    boolean aboveMax = setting.getMaxWorkHours() != null 
                            && totalMinutes > setting.getMaxWorkHours() * 60;
                    return belowMin || aboveMax;
                })
                .penalize(HardSoftScore.ofSoft(300), (setting, totalMinutes) -> {
                    int penalty = 0;
                    if (setting.getMinWorkHours() != null && totalMinutes < setting.getMinWorkHours() * 60) {
                        // 最小時間制約（最高優先度）
                        penalty += (setting.getMinWorkHours() * 60 - totalMinutes) * 1000;
                    }
                    if (setting.getMaxWorkHours() != null && totalMinutes > setting.getMaxWorkHours() * 60) {
                        // 最大時間制約（2番目優先度）
                        penalty += (totalMinutes - setting.getMaxWorkHours() * 60) * 500;
                    }
                    return Math.max(penalty, 1);
                })
                .asConstraint("Attendance: monthly work hours range");
    }

    private static java.time.LocalDate toLocalDateSafe(java.util.Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Date) return ((java.sql.Date) date).toLocalDate();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    // ===== 連勤ペナルティ（ATTENDANCE） =====
    // 7日連続の出勤を強く避けるためのソフト制約。
    // ベースとなる当日の割当（1件）に対し、前6日それぞれに同一従業員の割当が存在する場合にペナルティ。
    // 注意: 同一日に複数パターンが存在する場合、重複カウントの可能性があるが、同日ダブルブッキングはハード制約で抑止済み。
    private Constraint consecutiveSevenDaysPenalty(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(a -> a.getAssignedEmployee() != null)
                // d-1
                .ifExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(a -> a.getAssignedEmployee().getEmployeeCode(), b -> b.getAssignedEmployee() != null ? b.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((a, b) -> b.getAssignedEmployee() != null && b.getDate().equals(a.getDate().minusDays(1))))
                // d-2
                .ifExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(a -> a.getAssignedEmployee().getEmployeeCode(), b -> b.getAssignedEmployee() != null ? b.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((a, b) -> b.getAssignedEmployee() != null && b.getDate().equals(a.getDate().minusDays(2))))
                // d-3
                .ifExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(a -> a.getAssignedEmployee().getEmployeeCode(), b -> b.getAssignedEmployee() != null ? b.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((a, b) -> b.getAssignedEmployee() != null && b.getDate().equals(a.getDate().minusDays(3))))
                // d-4
                .ifExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(a -> a.getAssignedEmployee().getEmployeeCode(), b -> b.getAssignedEmployee() != null ? b.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((a, b) -> b.getAssignedEmployee() != null && b.getDate().equals(a.getDate().minusDays(4))))
                // d-5
                .ifExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(a -> a.getAssignedEmployee().getEmployeeCode(), b -> b.getAssignedEmployee() != null ? b.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((a, b) -> b.getAssignedEmployee() != null && b.getDate().equals(a.getDate().minusDays(5))))
                // d-6
                .ifExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(a -> a.getAssignedEmployee().getEmployeeCode(), b -> b.getAssignedEmployee() != null ? b.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((a, b) -> b.getAssignedEmployee() != null && b.getDate().equals(a.getDate().minusDays(6))))
                .penalize(HardSoftScore.ofSoft(10000))
                .asConstraint("Attendance: 7 consecutive days penalty");
    }
}
