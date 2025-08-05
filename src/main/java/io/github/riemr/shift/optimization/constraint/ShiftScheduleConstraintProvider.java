package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.domain.EmployeeRegisterSkill;
import io.github.riemr.shift.domain.EmployeeRequest;
import io.github.riemr.shift.domain.RegisterDemandQuarter;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class ShiftScheduleConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            // Hard constraints
            satisfyRegisterDemand(factory),
            forbidZeroSkill(factory),
            employeeNotDoubleBooked(factory),
            maxWorkMinutesPerDay(factory),
            maxWorkDaysPerMonth(factory),
            maxConsecutiveDays(factory),
            forbidRequestedDayOff(factory),
            enforceLunchBreak(factory),
            forbidNullBaseTimeEmployees(factory),

            // Soft constraints
            preferBaseWorkTimes(factory),
            minimizeDailyWorkers(factory),
            balanceWorkload(factory),
            assignContiguously(factory)
        };
    }

    private Constraint satisfyRegisterDemand(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.filtering((demand, sa) -> sa.getAssignedEmployee() != null))
                .groupBy((demand, sa) -> demand, ConstraintCollectors.countBi())
                .penalize(HardSoftScore.ONE_HARD,
                          (demand, assigned) -> Math.max(0, demand.getRequiredUnits() - assigned))
                .asConstraint("Unmet register demand");
    }

    private Constraint forbidZeroSkill(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .join(EmployeeRegisterSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeRegisterSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getRegisterNo, EmployeeRegisterSkill::getRegisterNo))
                .filter((sa, skill) -> skill.getSkillLevel() == 0)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Skill level zero");
    }

    private Constraint employeeNotDoubleBooked(ConstraintFactory f) {
        return f.forEachUniquePair(ShiftAssignmentPlanningEntity.class,
                Joiners.equal(ShiftAssignmentPlanningEntity::getAssignedEmployee),
                Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate),
                Joiners.equal(ShiftAssignmentPlanningEntity::getStartAt))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Employee double booked");
    }

    private Constraint maxWorkMinutesPerDay(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .filter((emp, date, list) -> {
                    int workMinutesExcludingBreaks = calculateWorkMinutesExcludingBreaks(list);
                    int maxWorkMinutes = emp.getMaxWorkMinutesDay() != null ? emp.getMaxWorkMinutesDay() : 480; // デフォルト8時間
                    return workMinutesExcludingBreaks > maxWorkMinutes;
                })
                .penalize(HardSoftScore.ONE_HARD, (emp, date, list) -> {
                    int workMinutesExcludingBreaks = calculateWorkMinutesExcludingBreaks(list);
                    int maxWorkMinutes = emp.getMaxWorkMinutesDay() != null ? emp.getMaxWorkMinutesDay() : 480;
                    return workMinutesExcludingBreaks - maxWorkMinutes;
                })
                .asConstraint("Exceed daily minutes excluding breaks");
    }

    private Constraint maxWorkDaysPerMonth(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, sa -> sa.getShiftDate().withDayOfMonth(1),
                         ConstraintCollectors.toSet(ShiftAssignmentPlanningEntity::getShiftDate))
                .filter((emp, month, daySet) -> emp.getMaxWorkDaysMonth() != null && daySet.size() > emp.getMaxWorkDaysMonth())
                .penalize(HardSoftScore.ONE_HARD,
                          (emp, month, daySet) -> daySet.size() - emp.getMaxWorkDaysMonth())
                .asConstraint("Exceed monthly workdays");
    }

    private Constraint maxConsecutiveDays(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee,
                         ConstraintCollectors.toSortedSet(ShiftAssignmentPlanningEntity::getShiftDate))
                .filter((emp, dates) -> hasRunLength(dates, 5))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("More than 4 consecutive days");
    }

    private static boolean hasRunLength(Set<LocalDate> dates, int limit) {
        LocalDate prev = null; int run = 0;
        for (LocalDate d : dates) {
            if (prev != null && prev.plusDays(1).equals(d)) {
                run++; if (run >= limit) return true;
            } else run = 1;
        }
        return false;
    }

    private Constraint forbidRequestedDayOff(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .join(EmployeeRequest.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeRequest::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate, EmployeeRequest::getRequestDate))
                .filter((sa, req) -> "off".equalsIgnoreCase(req.getRequestKind()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Assigned on requested day off");
    }

    private Constraint enforceLunchBreak(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .filter((emp, date, list) -> exceedsSixHoursWithoutBreak(list))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("No 1h break");
    }

    private static boolean exceedsSixHoursWithoutBreak(List<ShiftAssignmentPlanningEntity> list) {
        list.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
        int continuous = 1;
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).getStartAt().equals(list.get(i - 1).getEndAt())) {
                continuous++;
                if (continuous >= 24) return true; // 24*15min = 6h
            } else {
                if (Duration.between(list.get(i-1).getEndAt().toInstant(), list.get(i).getStartAt().toInstant()).toHours() >= 1) {
                    continuous = 0;
                } else {
                    continuous = 1;
                }
            }
        }
        return false;
    }

    private Constraint minimizeDailyWorkers(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toSet(ShiftAssignmentPlanningEntity::getAssignedEmployee))
                .penalize(HardSoftScore.ofSoft(10),
                          (date, empSet) -> empSet.size())
                .asConstraint("Minimize daily workers");
    }

    private Constraint balanceWorkload(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, ConstraintCollectors.count())
                .penalize(HardSoftScore.ofSoft(5), (emp, cnt) -> cnt.intValue() * cnt.intValue())
                .asConstraint("Balance workload");
    }

    private static int variancePenalty(List<Long> counts) {
        double avg = counts.stream().mapToLong(Long::longValue).average().orElse(0);
        double var = counts.stream().mapToDouble(c -> (c - avg) * (c - avg)).sum();
        return (int) Math.round(var);
    }

    private Constraint assignContiguously(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .penalize(HardSoftScore.ofSoft(1),
                          (emp, date, list) -> countGaps(list))
                .asConstraint("Fragmented blocks");
    }

    private static int countGaps(List<ShiftAssignmentPlanningEntity> list) {
        list.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
        int gaps = 0;
        for (int i = 1; i < list.size(); i++) {
            if (!list.get(i).getStartAt().equals(list.get(i - 1).getEndAt())) gaps++;
        }
        return gaps;
    }

    /**
     * 休憩時間を除いた実労働時間を計算する
     * 連続6時間労働の場合は1時間の休憩を差し引く
     */
    private static int calculateWorkMinutesExcludingBreaks(List<ShiftAssignmentPlanningEntity> list) {
        if (list.isEmpty()) return 0;
        
        list.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
        
        int totalWorkMinutes = list.stream().mapToInt(ShiftAssignmentPlanningEntity::getWorkMinutes).sum();
        int breakMinutes = 0;
        int continuousMinutes = 0;
        
        for (int i = 0; i < list.size(); i++) {
            ShiftAssignmentPlanningEntity current = list.get(i);
            continuousMinutes += current.getWorkMinutes();
            
            // 次のシフトとの間に1時間以上の休憩があるかチェック
            if (i < list.size() - 1) {
                ShiftAssignmentPlanningEntity next = list.get(i + 1);
                Duration breakDuration = Duration.between(current.getEndAt().toInstant(), next.getStartAt().toInstant());
                
                if (breakDuration.toMinutes() >= 60) {
                    // 6時間以上連続勤務していた場合、1時間の休憩時間を加算
                    if (continuousMinutes >= 360) { // 6時間 = 360分
                        breakMinutes += 60;
                    }
                    continuousMinutes = 0; // 連続勤務時間をリセット
                }
            }
        }
        
        // 最後のブロックも6時間以上の場合、休憩時間を加算
        if (continuousMinutes >= 360) {
            breakMinutes += 60;
        }
        
        return Math.max(0, totalWorkMinutes - breakMinutes);
    }

    /**
     * TODO: 将来的にはより柔軟な基本勤務時間制約に変更予定
     * 現在は暫定実装として、base_start_time/base_end_timeがnullの従業員はアサインしない
     */
    private Constraint forbidNullBaseTimeEmployees(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .filter(sa -> sa.getAssignedEmployee().getBaseStartTime() == null || 
                             sa.getAssignedEmployee().getBaseEndTime() == null)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Employee with null base times assigned");
    }

    /**
     * 従業員の基本勤務時間からの乖離を最小化する
     * base_start_time/base_end_timeに近い時間帯での勤務を優先
     */
    private Constraint preferBaseWorkTimes(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .filter(sa -> sa.getAssignedEmployee().getBaseStartTime() != null && 
                             sa.getAssignedEmployee().getBaseEndTime() != null)
                .penalize(HardSoftScore.ofSoft(100), sa -> {
                    // java.util.Date（TIME型）からLocalTimeに変換
                    LocalTime baseStart = sa.getAssignedEmployee().getBaseStartTime().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalTime();
                    LocalTime baseEnd = sa.getAssignedEmployee().getBaseEndTime().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalTime();
                    LocalTime shiftStart = sa.getStartAt().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalTime();
                    LocalTime shiftEnd = sa.getEndAt().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalTime();
                    
                    // 基本勤務時間との乖離を計算（分単位）
                    int startDeviation = Math.abs((int) Duration.between(baseStart, shiftStart).toMinutes());
                    int endDeviation = Math.abs((int) Duration.between(baseEnd, shiftEnd).toMinutes());
                    
                    // 12時間を超える乖離は12時間として扱う（24時間制での計算ミス対応）
                    startDeviation = Math.min(startDeviation, 720); // 12時間 = 720分
                    endDeviation = Math.min(endDeviation, 720);
                    
                    return startDeviation + endDeviation;
                })
                .asConstraint("Deviation from base work times");
    }
}



