package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlySetting;
import io.github.riemr.shift.optimization.entity.AttendanceGroupInfo;
import io.github.riemr.shift.optimization.entity.AttendanceGroupRuleType;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import io.github.riemr.shift.optimization.entity.RegisterDemandSlot;
import org.optaplanner.core.api.score.stream.tri.TriConstraintStream;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.YearMonth;

public class AttendanceConstraintProvider implements ConstraintProvider {
    /**
     * ATTENDANCE フェーズで使用する制約群を定義する。
     * ハード制約を優先しつつ、ソフト制約で需要充足や時間配分を最適化する。
     *
     * @param f 制約ファクトリ
     * @return 制約配列
     */
    @Override
    public Constraint[] defineConstraints(ConstraintFactory f) {
        return new Constraint[] {
                forbidRequestedDayOff(f),
                forbidWeeklyOffOrOutsideBase(f),
                employeeNotDoubleBooked(f),
                weeklyMaxWorkHoursHard(f),
                monthlyMaxWorkHoursHard(f),
                monthlyMinOffDaysHard(f),
                monthlyMaxOffDaysHard(f),
                monthlyMaxOffDaysHardNoWork(f),
                consecutiveSevenDaysHard(f),
                requirePatternAlignment(f),
                headcountBalance(f),
                // headcountShortageWhenNone(f),
                weeklyWorkHoursRange(f),
                monthlyWorkHoursRange(f),
                // consecutiveSevenDaysPenalty(f),
                overstaffLightPenalty(f),
                attendanceGroupMinOnDutyShortage(f),
                attendanceGroupMinOnDutyShortageWhenNone(f),
                attendanceGroupNoSameDayWork(f),
                attendanceGroupAllOrNothing(f)
        };
    }

    /**
     * 同一従業員・同一日でパターン時間帯が重なる割当を禁止する。
     *
     * @param f 制約ファクトリ
     * @return 重複割当禁止制約
     */
    private Constraint employeeNotDoubleBooked(ConstraintFactory f) {
        return f.forEachUniquePair(DailyPatternAssignmentEntity.class,
                        Joiners.equal(e -> e.getAssignedEmployee() != null ? e.getAssignedEmployee().getEmployeeCode() : null,
                                e -> e.getAssignedEmployee() != null ? e.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.equal(DailyPatternAssignmentEntity::getDate))
                .filter((a, b) -> overlaps(a, b))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Attendance double booking");
    }

    /**
     * パターン時間帯が重なっているかを判定する。
     *
     * @param a パターン割当 A
     * @param b パターン割当 B
     * @return 重なる場合は true
     */
    private static boolean overlaps(DailyPatternAssignmentEntity a, DailyPatternAssignmentEntity b) {
        if (a.getAssignedEmployee() == null || b.getAssignedEmployee() == null) return false;
        if (!a.getDate().equals(b.getDate())) return false;
        // [start, end) の重なり
        return a.getPatternStart().isBefore(b.getPatternEnd()) && b.getPatternStart().isBefore(a.getPatternEnd());
    }

    /**
     * 希望休の日に割当がある場合はハード制約違反とする。
     *
     * @param f 制約ファクトリ
     * @return 希望休違反制約
     */
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

    /**
     * 週次の勤務可能時間外や曜日 OFF に割当がある場合はハード制約違反とする。
     *
     * @param f 制約ファクトリ
     * @return 週次勤務可能時間違反制約
     */
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

    /**
     * パターン整合性をソフトで評価する（優先度 2 以上のパターンと一致しない場合に罰則）。
     *
     * @param f 制約ファクトリ
     * @return パターン整合性制約
     */
    private Constraint requirePatternAlignment(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(e -> e.getAssignedEmployee() != null)
                .join(EmployeeShiftPattern.class,
                        Joiners.equal(e -> e.getAssignedEmployee().getEmployeeCode(), EmployeeShiftPattern::getEmployeeCode),
                        Joiners.filtering((e, p) -> !Boolean.FALSE.equals(p.getActive())
                                && p.getStartTime().toLocalTime().equals(e.getPatternStart())
                                && p.getEndTime().toLocalTime().equals(e.getPatternEnd())))
                .groupBy((e, p) -> e,
                        ConstraintCollectors.max((DailyPatternAssignmentEntity e, EmployeeShiftPattern p) -> p.getPriority()))
                // 優先度が高いほどペナルティを小さくして「割当が安い」状態にする
                .penalize(HardSoftScore.ofSoft(10), (e, priority) -> {
                    if (priority == null) return 0;
                    int maxPriority = 4;
                    return Math.max(0, maxPriority - priority.intValue());
                })
                .asConstraint("Pattern alignment preferred");
    }

    /**
     * 需要スロットに対する人数過不足を評価する（ソフト制約）。
     *
     * @param f 制約ファクトリ
     * @return 人数バランス制約
     */
    private Constraint headcountBalance(ConstraintFactory f) {
        return f.forEach(RegisterDemandSlot.class)
                .join(DailyPatternAssignmentEntity.class,
                        Joiners.equal(RegisterDemandSlot::getStoreCode, DailyPatternAssignmentEntity::getStoreCode),
                        Joiners.equal(RegisterDemandSlot::getDemandDate, DailyPatternAssignmentEntity::getDate),
                        Joiners.filtering((d, e) -> e.getAssignedEmployee() != null
                                && timeWithin(e, d.getSlotTime())))
                .groupBy((d, e) -> d, ConstraintCollectors.countBi())
                .penalize(HardSoftScore.ofSoft(200), (d, assigned) -> {
                    int required = d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits());
                    int diff = assigned - required;
                    if (diff < 0) return (-diff) * 2;
                    if (diff > 0) return diff;
                    return 0;
                })
                .asConstraint("Quarter headcount balance");
    }

    /**
     * 需要スロットに対して過剰配置がある場合に軽いペナルティを課す。
     *
     * @param f 制約ファクトリ
     * @return 過剰配置ペナルティ制約
     */
    private Constraint overstaffLightPenalty(ConstraintFactory f) {
        return f.forEach(RegisterDemandSlot.class)
                .join(DailyPatternAssignmentEntity.class,
                        Joiners.equal(RegisterDemandSlot::getStoreCode, DailyPatternAssignmentEntity::getStoreCode),
                        Joiners.equal(RegisterDemandSlot::getDemandDate, DailyPatternAssignmentEntity::getDate),
                        Joiners.filtering((d, e) -> e.getAssignedEmployee() != null && timeWithin(e, d.getSlotTime())))
                .groupBy((d, e) -> d, ConstraintCollectors.countBi())
                .filter((d, assigned) -> assigned > (d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits())))
                .penalize(HardSoftScore.ofSoft(10), (d, assigned) -> assigned - (d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits())))
                .asConstraint("Attendance: overstaff light penalty");
    }

    /**
     * 需要スロットに誰も割り当てがない場合に不足分を強く誘導する。
     *
     * @param f 制約ファクトリ
     * @return 不足誘導制約
     */
    private Constraint headcountShortageWhenNone(ConstraintFactory f) {
        return f.forEach(RegisterDemandSlot.class)
                .ifNotExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(RegisterDemandSlot::getStoreCode, DailyPatternAssignmentEntity::getStoreCode),
                        Joiners.equal(RegisterDemandSlot::getDemandDate, DailyPatternAssignmentEntity::getDate),
                        Joiners.filtering((d, e) -> e.getAssignedEmployee() != null && timeWithin(e, d.getSlotTime())))
                .penalize(HardSoftScore.ofSoft(120), d -> d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits()) * 2)
                .asConstraint("Quarter headcount shortage (none)");
    }

    /**
     * 指定スロット時刻がパターンの時間帯に含まれるかを判定する。
     *
     * @param e パターン割当
     * @param slot 需要スロット開始時刻
     * @return パターン内なら true
     */
    private static boolean timeWithin(DailyPatternAssignmentEntity e, LocalTime slot) {
        return (slot.equals(e.getPatternStart()) || slot.isAfter(e.getPatternStart()))
                && slot.isBefore(e.getPatternEnd());
    }

    private TriConstraintStream<AttendanceGroupInfo, LocalDate, java.util.Set<String>> onDutyMembersByConstraintAndDate(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(e -> e.getAssignedEmployee() != null)
                .join(AttendanceGroupInfo.class,
                        Joiners.filtering((e, info) -> info.hasMember(e.getAssignedEmployee().getEmployeeCode())))
                .join(LocalDate.class,
                        Joiners.equal((e, info) -> e.getDate(), d -> d))
                .groupBy((e, info, date) -> info,
                        (e, info, date) -> date,
                        ConstraintCollectors.toSet((e, info, date) -> e.getAssignedEmployee().getEmployeeCode()));
    }

    private Constraint attendanceGroupMinOnDutyShortage(ConstraintFactory f) {
        return onDutyMembersByConstraintAndDate(f)
                .filter((info, date, members) -> info.getRuleType() == AttendanceGroupRuleType.MIN_ON_DUTY
                        && info.getMinOnDuty() != null
                        && members.size() < info.getMinOnDuty())
                .penalize(HardSoftScore.ONE_SOFT,
                        (info, date, members) -> info.getMinOnDuty() - members.size())
                .asConstraint("Attendance group min on duty shortage");
    }

    private Constraint attendanceGroupMinOnDutyShortageWhenNone(ConstraintFactory f) {
        return f.forEach(LocalDate.class)
                .join(AttendanceGroupInfo.class,
                        Joiners.filtering((date, info) -> info.getRuleType() == AttendanceGroupRuleType.MIN_ON_DUTY
                                && info.getMinOnDuty() != null
                                && info.getMinOnDuty() > 0))
                .ifNotExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal((date, info) -> date, DailyPatternAssignmentEntity::getDate),
                        Joiners.filtering((date, info, e) -> e.getAssignedEmployee() != null
                                && info.hasMember(e.getAssignedEmployee().getEmployeeCode())))
                .penalize(HardSoftScore.ONE_SOFT,
                        (date, info) -> info.getMinOnDuty())
                .asConstraint("Attendance group min on duty shortage (none)");
    }

    private Constraint attendanceGroupNoSameDayWork(ConstraintFactory f) {
        return onDutyMembersByConstraintAndDate(f)
                .filter((info, date, members) -> info.getRuleType() == AttendanceGroupRuleType.NO_SAME_DAY_WORK
                        && info.getMemberCount() > 0
                        && members.size() == info.getMemberCount())
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Attendance group no same day work");
    }

    private Constraint attendanceGroupAllOrNothing(ConstraintFactory f) {
        return onDutyMembersByConstraintAndDate(f)
                .filter((info, date, members) -> info.getRuleType() == AttendanceGroupRuleType.ALL_OR_NOTHING
                        && members.size() > 0
                        && members.size() < info.getMemberCount())
                .penalize(HardSoftScore.ONE_SOFT,
                        (info, date, members) -> Math.min(members.size(), info.getMemberCount() - members.size()))
                .asConstraint("Attendance group all or nothing");
    }

    // ===== 労働時間制約（ATTENDANCE） =====
    
    /**
     * パターン割り当ての労働分数を計算
     *
     * @param pattern パターン割当
     * @return 分単位の労働時間
     */
    private static int calculatePatternMinutes(DailyPatternAssignmentEntity pattern) {
        return (int) java.time.Duration.between(pattern.getPatternStart(), pattern.getPatternEnd()).toMinutes();
    }
    
    /**
     * 指定日付の週開始日を取得（月曜日起点）
     *
     * @param date 対象日
     * @return 週開始日（月曜日）
     */
    private static java.time.LocalDate getWeekStart(java.time.LocalDate date) {
        int dayOfWeek = date.getDayOfWeek().getValue(); // 1=月曜日
        return date.minusDays(dayOfWeek - 1);
    }
    
    /**
     * 週次労働時間制約（ソフト制約）
     *
     * @param f 制約ファクトリ
     * @return 週次労働時間の最小不足制約
     */
    private Constraint weeklyWorkHoursRange(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(pattern -> pattern.getAssignedEmployee() != null)
                .groupBy(DailyPatternAssignmentEntity::getAssignedEmployee,
                        pattern -> getWeekStart(pattern.getDate()),
                        ConstraintCollectors.toList())
                .filter((emp, weekStart, patterns) -> {
                    int totalMinutes = patterns.stream()
                            .mapToInt(AttendanceConstraintProvider::calculatePatternMinutes)
                            .sum();
                    // 月をまたぐ週は最小制約を無視
                    boolean crossesMonth = !YearMonth.from(weekStart)
                            .equals(YearMonth.from(weekStart.plusDays(6)));
                    boolean belowMin = !crossesMonth && emp.getMinWorkHoursWeek() != null 
                            && totalMinutes < emp.getMinWorkHoursWeek() * 60;
                    return belowMin;
                })
                .penalize(HardSoftScore.ofSoft(200), (emp, weekStart, patterns) -> {
                    int totalMinutes = patterns.stream()
                            .mapToInt(AttendanceConstraintProvider::calculatePatternMinutes)
                            .sum();
                    int penalty = 0;
                    // 月をまたぐ週は最小制約を無視
                    boolean crossesMonth = !YearMonth.from(weekStart)
                            .equals(YearMonth.from(weekStart.plusDays(6)));
                    if (!crossesMonth && emp.getMinWorkHoursWeek() != null 
                            && totalMinutes < emp.getMinWorkHoursWeek() * 60) {
                        // 最小時間制約（最高優先度）
                        penalty += (emp.getMinWorkHoursWeek() * 60 - totalMinutes) * 500;
                    }
                    return Math.max(penalty, 1);
                })
                .asConstraint("Attendance: weekly work hours range");
    }
    
    /**
     * 月次労働時間制約（ソフト制約）
     *
     * @param f 制約ファクトリ
     * @return 月次労働時間の最小不足制約
     */
    private Constraint monthlyWorkHoursRange(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(pattern -> pattern.getAssignedEmployee() != null)
                .join(EmployeeMonthlySetting.class,
                        Joiners.equal(p -> p.getAssignedEmployee().getEmployeeCode(),
                                     EmployeeMonthlySetting::getEmployeeCode),
                        Joiners.filtering((p, setting) -> 
                            YearMonth.from(p.getDate()).equals(
                                YearMonth.from(setting.getMonthStart()
                                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDate()))))
                .groupBy((pattern, setting) -> setting,
                        ConstraintCollectors.sum((pattern, setting) -> 
                            calculatePatternMinutes(pattern)))
                .filter((setting, totalMinutes) -> {
                    boolean belowMin = setting.getMinWorkHours() != null 
                            && totalMinutes < setting.getMinWorkHours() * 60;
                    return belowMin;
                })
                .penalize(HardSoftScore.ofSoft(200), (setting, totalMinutes) -> {
                    int penalty = 0;
                    if (setting.getMinWorkHours() != null && totalMinutes < setting.getMinWorkHours() * 60) {
                        // 最小時間制約（最高優先度）
                        penalty += (setting.getMinWorkHours() * 60 - totalMinutes) * 500;
                    }
                    return Math.max(penalty, 1);
                })
                .asConstraint("Attendance: monthly work hours range");
    }

    /**
     * 週次の最大勤務時間超過をハード制約として禁止する。
     *
     * @param f 制約ファクトリ
     * @return 週次最大勤務時間のハード制約
     */
    private Constraint weeklyMaxWorkHoursHard(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(pattern -> pattern.getAssignedEmployee() != null)
                .groupBy(DailyPatternAssignmentEntity::getAssignedEmployee,
                        pattern -> getWeekStart(pattern.getDate()),
                        ConstraintCollectors.sum(AttendanceConstraintProvider::calculatePatternMinutes))
                .filter((emp, weekStart, totalMinutes) ->
                        emp.getMaxWorkHoursWeek() != null
                                && totalMinutes > emp.getMaxWorkHoursWeek() * 60)
                .penalize(HardSoftScore.ofSoft(200), (emp, weekStart, totalMinutes) -> {
                    int overMinutes = totalMinutes - emp.getMaxWorkHoursWeek() * 60;
                    return Math.max(0, overMinutes);
                })
                .asConstraint("Attendance: weekly max hours hard");
    }

    /**
     * 月次の最大勤務時間超過をハード制約として禁止する。
     *
     * @param f 制約ファクトリ
     * @return 月次最大勤務時間のハード制約
     */
    private Constraint monthlyMaxWorkHoursHard(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(pattern -> pattern.getAssignedEmployee() != null)
                .join(EmployeeMonthlySetting.class,
                        Joiners.equal(p -> p.getAssignedEmployee().getEmployeeCode(),
                                EmployeeMonthlySetting::getEmployeeCode),
                        Joiners.filtering((p, setting) ->
                                YearMonth.from(p.getDate()).equals(
                                        YearMonth.from(setting.getMonthStart()
                                                .toInstant().atZone(ZoneId.systemDefault()).toLocalDate()))))
                .groupBy((pattern, setting) -> setting,
                        ConstraintCollectors.sum((pattern, setting) ->
                                calculatePatternMinutes(pattern)))
                .filter((setting, totalMinutes) ->
                        setting.getMaxWorkHours() != null
                                && totalMinutes > setting.getMaxWorkHours() * 60)
                .penalize(HardSoftScore.ofSoft(200), (setting, totalMinutes) -> {
                    int overMinutes = totalMinutes - setting.getMaxWorkHours() * 60;
                    return Math.max(0, overMinutes);
                })
                .asConstraint("Attendance: monthly max hours hard");
    }

    /**
     * 月次の最小公休日数をハード制約として守る。
     *
     * @param f 制約ファクトリ
     * @return 月次最小公休日数のハード制約
     */
    private Constraint monthlyMinOffDaysHard(ConstraintFactory f) {
        return f.forEach(EmployeeMonthlySetting.class)
                .join(DailyPatternAssignmentEntity.class,
                        Joiners.equal(EmployeeMonthlySetting::getEmployeeCode,
                                p -> p.getAssignedEmployee() != null ? p.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((setting, p) ->
                                YearMonth.from(p.getDate()).equals(getMonthStart(setting))))
                .groupBy((setting, p) -> setting,
                        ConstraintCollectors.toSet((setting, p) -> p.getDate()))
                .filter((setting, dates) -> setting.getMinOffDays() != null)
                .penalize(HardSoftScore.ONE_HARD, (setting, dates) -> {
                    int workedDays = dates.size();
                    int totalDays = getMonthStart(setting).lengthOfMonth();
                    int offDays = totalDays - workedDays;
                    int diff = setting.getMinOffDays() - offDays;
                    return diff > 0 ? diff : 0;
                })
                .asConstraint("Attendance: monthly min off days hard");
    }

    /**
     * 月次の最大公休日数をハード制約として守る。
     *
     * @param f 制約ファクトリ
     * @return 月次最大公休日数のハード制約
     */
    private Constraint monthlyMaxOffDaysHard(ConstraintFactory f) {
        return f.forEach(EmployeeMonthlySetting.class)
                .join(DailyPatternAssignmentEntity.class,
                        Joiners.equal(EmployeeMonthlySetting::getEmployeeCode,
                                p -> p.getAssignedEmployee() != null ? p.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((setting, p) ->
                                YearMonth.from(p.getDate()).equals(getMonthStart(setting))))
                .groupBy((setting, p) -> setting,
                        ConstraintCollectors.toSet((setting, p) -> p.getDate()))
                .filter((setting, dates) -> setting.getMaxOffDays() != null)
                .penalize(HardSoftScore.ONE_HARD, (setting, dates) -> {
                    int workedDays = dates.size();
                    int totalDays = getMonthStart(setting).lengthOfMonth();
                    int offDays = totalDays - workedDays;
                    int diff = offDays - setting.getMaxOffDays();
                    return diff > 0 ? diff : 0;
                })
                .asConstraint("Attendance: monthly max off days hard");
    }

    /**
     * TODO いる？
     * 月内に出勤が無い場合でも最大公休日数を超過しないようにする。
     *
     * @param f 制約ファクトリ
     * @return 月次最大公休日数（出勤なし）のハード制約
     */
    private Constraint monthlyMaxOffDaysHardNoWork(ConstraintFactory f) {
        return f.forEach(EmployeeMonthlySetting.class)
                .ifNotExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(EmployeeMonthlySetting::getEmployeeCode,
                                p -> p.getAssignedEmployee() != null ? p.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.filtering((setting, p) ->
                                YearMonth.from(p.getDate()).equals(getMonthStart(setting))))
                .filter(setting -> setting.getMaxOffDays() != null)
                .penalize(HardSoftScore.ONE_HARD, setting -> {
                    int totalDays = getMonthStart(setting).lengthOfMonth();
                    int offDays = totalDays;
                    int diff = offDays - setting.getMaxOffDays();
                    return diff > 0 ? diff : 0;
                })
                .asConstraint("Attendance: monthly max off days hard (no work)");
    }

    /**
     * 7日連続出勤をハード制約として禁止する。
     *
     * @param f 制約ファクトリ
     * @return 7連勤のハード制約
     */
    private Constraint consecutiveSevenDaysHard(ConstraintFactory f) {
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
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Attendance: 7 consecutive days hard");
    }

    /**
     * java.util.Date を LocalDate に安全に変換する。
     *
     * @param date 対象日付
     * @return LocalDate（null可）
     */
    private static LocalDate toLocalDateSafe(java.util.Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Date) return ((java.sql.Date) date).toLocalDate();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 月次設定の対象月を取得する。
     *
     * @param setting 月次設定
     * @return 対象月の YearMonth
     */
    private static YearMonth getMonthStart(EmployeeMonthlySetting setting) {
        LocalDate date = setting.getMonthStart().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return YearMonth.from(date);
    }

    // ===== 連勤ペナルティ（ATTENDANCE） =====
    // 7日連続の出勤を強く避けるためのソフト制約。
    // ベースとなる当日の割当（1件）に対し、前6日それぞれに同一従業員の割当が存在する場合にペナルティ。
    // 注意: 同一日に複数パターンが存在する場合、重複カウントの可能性があるが、同日ダブルブッキングはハード制約で抑止済み。
    /**
     * 7日連続出勤を抑制するソフト制約。
     *
     * @param f 制約ファクトリ
     * @return 7連勤ペナルティ制約
     */
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
