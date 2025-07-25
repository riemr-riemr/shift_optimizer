package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基本的な制約セット – 最小限の可行解を生成する。
 * <p>
 * Hard:
 * <ul>
 * <li>各レジスターのシフトは必ず 1 人割り当て</li>
 * <li>同じレジスター・同時刻に重複割当しない</li>
 * <li>同一従業員が同日に複数レジスターに入らない</li>
 * <li>従業員の1日の労働時間がmax_work_minutes_dayを超えない</li>
 * </ul>
 * Soft:
 * <ul>
 * <li>従業員ごとのシフト数を均等化</li>
 * <li>従業員のmax_work_minutes_day分まとめてアサイン</li>
 * <li>6時間以上の労働の場合には、1時間の休憩をなるべく中間で挟む</li>
 * <li>1カ月の労働日数がemployeeのmax_work_days_monthを超える場合はペナルティ</li>
 * </ul>
 */
public class ShiftScheduleConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                shiftMustBeAssigned(factory),
                registerNotDoubleBooked(factory),
                employeeNotOverlapping(factory), // 従業員シフト重複制約
                employeeMaxOneShiftPerDay(factory),
                employeeDailyWorkMinutesLimit(factory), // New Hard Constraint
                balanceWorkload(factory),
                assignContiguously(factory),
                breakForLongShifts(factory),
                monthlyWorkDayLimit(factory),
                minimizeRegisterSwitches(factory)
        };
    }

    /** Hard 1: 未割当シフトへのペナルティ */
    private Constraint shiftMustBeAssigned(ConstraintFactory factory) {
        return factory.from(ShiftAssignmentPlanningEntity.class)
                .filter(a -> a.getAssignedEmployee() == null)
                .penalize("Unassigned shift", HardSoftScore.ONE_HARD);
    }

    /** Hard 2: 同じレジスタ・日時に 2 人以上割り当てない */
    private Constraint registerNotDoubleBooked(ConstraintFactory factory) {
        return factory.fromUniquePair(ShiftAssignmentPlanningEntity.class,
                Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate),
                Joiners.equal(ShiftAssignmentPlanningEntity::getRegisterNo),
                Joiners.overlapping(a -> a.getOrigin().getStartAt(), a -> a.getOrigin().getEndAt()))
                .penalize("Register double‐booked", HardSoftScore.ONE_HARD);
    }

    /** Hard 3: 従業員が同日に複数レジに入らない */
    private Constraint employeeMaxOneShiftPerDay(ConstraintFactory factory) {
        return factory.fromUniquePair(ShiftAssignmentPlanningEntity.class,
                Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate),
                Joiners.filtering((a, b) -> a.getAssignedEmployee() != null && b.getAssignedEmployee() != null &&
                        a.getAssignedEmployee().getEmployeeCode().equals(b.getAssignedEmployee().getEmployeeCode())))
                .penalize("Employee double shift same day", HardSoftScore.ONE_HARD);
    }

    /** Hard 4: 従業員のシフトが時間的に重複しない */
    private Constraint employeeNotOverlapping(ConstraintFactory factory) {
        return factory.fromUniquePair(ShiftAssignmentPlanningEntity.class,
                Joiners.equal(ShiftAssignmentPlanningEntity::getAssignedEmployee),
                Joiners.overlapping(a -> a.getOrigin().getStartAt(), a -> a.getOrigin().getEndAt()))
                .penalize("Employee overlapping shifts", HardSoftScore.ONE_HARD);
    }

    /** Hard 5: 従業員の1日の労働時間がmax_work_minutes_dayを超えない */
    private Constraint employeeDailyWorkMinutesLimit(ConstraintFactory factory) {
        return factory.from(ShiftAssignmentPlanningEntity.class)
                .filter(a -> a.getAssignedEmployee() != null)
                .groupBy(a -> a.getAssignedEmployee(),
                        a -> a.getShiftDate(),
                        ConstraintCollectors.sumDuration((shiftAssignmentPlanningEntity) -> Duration.between(
                                shiftAssignmentPlanningEntity.getOrigin().getStartAt().toInstant()
                                        .atZone(ZoneId.systemDefault()).toLocalDateTime(),
                                shiftAssignmentPlanningEntity.getOrigin().getEndAt().toInstant()
                                        .atZone(ZoneId.systemDefault()).toLocalDateTime())))
                .filter((employee, shiftDate, totalDuration) -> employee.getMaxWorkMinutesDay() != null
                        && totalDuration.toMinutes() > employee.getMaxWorkMinutesDay())
                .penalize("Employee daily work minutes limit", HardSoftScore.ONE_HARD,
                        (employee, shiftDate,
                                totalDuration) -> (int) (totalDuration.toMinutes() - employee.getMaxWorkMinutesDay()));
    }

    /** Soft 1: シフト数を各従業員で均等化（分散を抑える） */
    private Constraint balanceWorkload(ConstraintFactory factory) {
        return factory.from(ShiftAssignmentPlanningEntity.class)
                .filter(a -> a.getAssignedEmployee() != null)
                .groupBy(a -> a.getAssignedEmployee().getEmployeeCode(), ConstraintCollectors.count())
                .penalize("Balance workload", HardSoftScore.ONE_SOFT, (employeeCode, count) -> count * count);
    }

    /** Soft 2: 従業員のmax_work_minutes_day分まとめてアサイン */
    private Constraint assignContiguously(ConstraintFactory factory) {
        return factory.from(ShiftAssignmentPlanningEntity.class)
                .filter(a -> a.getAssignedEmployee() != null)
                .groupBy(a -> a.getAssignedEmployee(),
                         a -> a.getShiftDate(),
                         ConstraintCollectors.toList())
                .penalize("Fragmented blocks", HardSoftScore.ONE_SOFT, (emp, date, list) -> {
                    list.sort(Comparator.comparing(sa -> sa.getOrigin().getStartAt()));
    
                    int blocks = 1;
                    for (int i = 1; i < list.size(); i++) {
                        // 直前の endAt と次の startAt が 15 分超 ⇒ 新ブロック
                        long gap = Duration.between(
                                list.get(i - 1).getOrigin().getEndAt().toInstant(),
                                list.get(i).getOrigin().getStartAt().toInstant()
                        ).toMinutes();
                        if (gap > 15) blocks++;
                    }
                    return blocks - 1;        // ブロック数−1 をペナルティ
                });
    }    

    /** Soft 3: 6時間以上の労働の場合には、1時間の休憩をなるべく中間で挟む */
    private Constraint breakForLongShifts(ConstraintFactory factory) {
        return factory.from(ShiftAssignmentPlanningEntity.class)
                .filter(a -> a.getAssignedEmployee() != null)
                .groupBy(a -> a.getAssignedEmployee().getEmployeeCode(),
                        a -> a.getShiftDate(),
                        ConstraintCollectors.toList()) // Collect all assignments for the day
                .penalize("Break for long shifts", HardSoftScore.ONE_SOFT, (employeeCode, shiftDate, assignments) -> {
                    // Sort assignments by start time
                    assignments.sort((a1, a2) -> a1.getOrigin().getStartAt().compareTo(a2.getOrigin().getStartAt()));

                    long totalWorkMinutes = 0;
                    LocalDateTime firstShiftStart = null;
                    LocalDateTime lastShiftEnd = null;

                    for (ShiftAssignmentPlanningEntity assignment : assignments) {
                        LocalDateTime currentStart = assignment.getOrigin().getStartAt().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime();
                        LocalDateTime currentEnd = assignment.getOrigin().getEndAt().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime();

                        totalWorkMinutes += Duration.between(currentStart, currentEnd).toMinutes();

                        if (firstShiftStart == null || currentStart.isBefore(firstShiftStart)) {
                            firstShiftStart = currentStart;
                        }
                        if (lastShiftEnd == null || currentEnd.isAfter(lastShiftEnd)) {
                            lastShiftEnd = currentEnd;
                        }
                    }

                    if (totalWorkMinutes < 6 * 60) { // Only apply for shifts 6 hours (360 minutes) or longer
                        return 0;
                    }

                    // Calculate the total span of the shifts for the day
                    long totalSpanMinutes = Duration.between(firstShiftStart, lastShiftEnd).toMinutes();

                    // Ideal break start: roughly in the middle of the total span
                    LocalDateTime idealBreakStart = firstShiftStart.plusMinutes(totalSpanMinutes / 2 - 30); // 30 min
                                                                                                            // before
                                                                                                            // middle
                                                                                                            // for 1
                                                                                                            // hour
                                                                                                            // break
                    LocalDateTime idealBreakEnd = idealBreakStart.plusMinutes(60); // 1 hour break

                    boolean hasBreakInIdealWindow = false;
                    // Check for a 1-hour break (60 minutes) within the ideal window
                    // This checks if there's any unassigned time within the ideal break window
                    // by checking if any assignment overlaps with the ideal break window.
                    boolean overlapsWithIdealBreak = false;
                    for (ShiftAssignmentPlanningEntity assignment : assignments) {
                        LocalDateTime assignmentStart = assignment.getOrigin().getStartAt().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime();
                        LocalDateTime assignmentEnd = assignment.getOrigin().getEndAt().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime();

                        // Check for overlap: (start1 < end2) && (end1 > start2)
                        if (assignmentStart.isBefore(idealBreakEnd) && assignmentEnd.isAfter(idealBreakStart)) {
                            overlapsWithIdealBreak = true;
                            break;
                        }
                    }

                    // If there's no overlap with the ideal break window, it means there's a break
                    // there.
                    hasBreakInIdealWindow = !overlapsWithIdealBreak;

                    // Penalize if total work is >= 6 hours and no 1-hour break was found in the
                    // ideal window
                    return hasBreakInIdealWindow ? 0 : 200; // Increased penalty for missing break
                });
    }

    /** Soft 4: 1カ月の労働日数がemployeeのmax_work_days_monthを超える場合はペナルティ */
    private Constraint monthlyWorkDayLimit(ConstraintFactory factory) {
        return factory.from(ShiftAssignmentPlanningEntity.class)
                .filter(a -> a.getAssignedEmployee() != null)
                .groupBy(a -> a.getAssignedEmployee(),
                        ConstraintCollectors.countDistinct(ShiftAssignmentPlanningEntity::getShiftDate))
                .penalize("Monthly work day limit", HardSoftScore.ONE_SOFT, (employee, distinctDays) -> {
                    if (employee.getMaxWorkDaysMonth() != null && distinctDays > employee.getMaxWorkDaysMonth()) {
                        return (distinctDays - employee.getMaxWorkDaysMonth()) * 100; // Penalize per extra day
                    }
                    return 0;
                });
    }

    /** Soft 5: 同じレジでの従業員スイッチを抑制 */
    private Constraint minimizeRegisterSwitches(ConstraintFactory factory) {
        return factory.from(ShiftAssignmentPlanningEntity.class)
                .filter(a -> a.getAssignedEmployee() != null)
                .groupBy(a -> a.getShiftDate(),
                        a -> a.getRegisterNo(),
                        ConstraintCollectors.toList())
                .penalize("Switch employee on register",
                        HardSoftScore.ONE_SOFT,
                        (date, regNo, list) -> {
                            // 時間順に並べ替え
                            list.sort(Comparator.comparing(sa -> sa.getOrigin().getStartAt()));
                            int switches = 0;
                            for (int i = 0; i < list.size() - 1; i++) {
                                if (!Objects.equals(
                                        list.get(i).getAssignedEmployee(),
                                        list.get(i + 1).getAssignedEmployee())) {
                                    switches++;
                                }
                            }
                            return switches; // スイッチ 1 回＝ +1 ペナルティ
                        });
    }
}