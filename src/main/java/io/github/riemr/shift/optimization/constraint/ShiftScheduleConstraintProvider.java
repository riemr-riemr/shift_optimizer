package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class ShiftScheduleConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            // Hard constraints
            forbidZeroSkill(factory),
            employeeNotDoubleBooked(factory),
            maxWorkMinutesPerDay(factory),
            maxWorkDaysPerMonth(factory),
            maxConsecutiveDays(factory),
            forbidRequestedDayOff(factory),
            enforceLunchBreak(factory),
            forbidNullBaseTimeEmployees(factory),
            ensureWeeklyRestDays(factory),

            // Soft constraints
            satisfyRegisterDemand(factory),
            preferHigherSkillLevel(factory),
            preferBaseWorkTimes(factory),
            minimizeDailyWorkers(factory),
            balanceWorkload(factory),
            assignContiguously(factory),
            minimizeStaffingVariance(factory),
            minimizeRegisterSwitching(factory),
            preferConsistentRegisterAssignment(factory)
        };
    }

    private Constraint satisfyRegisterDemand(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.filtering((demand, sa) -> sa.getAssignedEmployee() != null))
                .groupBy((demand, sa) -> demand, ConstraintCollectors.countBi())
                .penalize(HardSoftScore.ofSoft(1000), // 高い重みでソフト制約に変更
                          (demand, assigned) -> {
                              int shortage = demand.getRequiredUnits() - assigned;
                              if (shortage > 0) {
                                  // 人員不足の場合は高いペナルティ
                                  return shortage * 100;
                              } else {
                                  // 人員過多の場合は軽いペナルティ（人数不足よりはまし）
                                  int overstaffing = assigned - demand.getRequiredUnits();
                                  return overstaffing * 50;
                              }
                          })
                .asConstraint("Register demand balance");
    }

    private Constraint forbidZeroSkill(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .join(EmployeeRegisterSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeRegisterSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getRegisterNo, EmployeeRegisterSkill::getRegisterNo))
                .filter((sa, skill) -> skill.getSkillLevel() != null && 
                                       (skill.getSkillLevel() == 0 || skill.getSkillLevel() == 1))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Forbidden skill assignment (0: auto-assign disabled, 1: assignment forbidden)");
    }

    /**
     * より高いスキルレベルの従業員を優先的に割り当てる制約
     * スキルレベル: 2(低) < 3(中) < 4(高)
     */
    private Constraint preferHigherSkillLevel(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .join(EmployeeRegisterSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeRegisterSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getRegisterNo, EmployeeRegisterSkill::getRegisterNo))
                .filter((sa, skill) -> skill.getSkillLevel() != null && 
                                       skill.getSkillLevel() >= 2 && skill.getSkillLevel() <= 4)
                .reward(HardSoftScore.ofSoft(200), 
                        (sa, skill) -> {
                            // スキルレベルが高いほど高い報酬
                            // レベル2: 200, レベル3: 400, レベル4: 600
                            return (skill.getSkillLevel() - 1) * 200;
                        })
                .asConstraint("Prefer higher skill level assignment");
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
                        Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate, 
                                     req -> req.getRequestDate().toInstant()
                                           .atZone(ZoneId.systemDefault())
                                           .toLocalDate()))
                .filter((sa, req) -> {
                    boolean isOff = "off".equalsIgnoreCase(req.getRequestKind());
                    if (isOff) {
                        System.out.println("CONSTRAINT VIOLATION: Employee " + sa.getAssignedEmployee().getEmployeeCode() + 
                                         " assigned on requested day off: " + sa.getShiftDate());
                    }
                    return isOff;
                })
                .penalize(HardSoftScore.of(10000, 0))
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
        if (list.isEmpty()) return false;
        list.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));

        var blockStart = list.get(0).getStartAt().toInstant();
        var prevEnd = list.get(0).getEndAt().toInstant();

        // Check the first block as well
        if (Duration.between(blockStart, prevEnd).toMinutes() >= 360) return true;

        for (int i = 1; i < list.size(); i++) {
            var start = list.get(i).getStartAt().toInstant();
            var end = list.get(i).getEndAt().toInstant();
            long gap = Duration.between(prevEnd, start).toMinutes();

            if (gap >= 60) {
                // A real lunch break resets the continuous window
                blockStart = start;
            }
            // else: gap < 60 means still considered continuous without proper break

            prevEnd = end;
            if (Duration.between(blockStart, prevEnd).toMinutes() >= 360) return true;
        }
        return false;
    }

    private Constraint minimizeDailyWorkers(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toSet(ShiftAssignmentPlanningEntity::getAssignedEmployee))
                .penalize(HardSoftScore.ofSoft(500),
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
        // Each planning entity represents actual worked time (15-minute slot).
        // Summing them already excludes gaps/breaks.
        return list.stream().mapToInt(ShiftAssignmentPlanningEntity::getWorkMinutes).sum();
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

    /**
     * 週に2日以上の休日を保証するハード制約
     * 各従業員について、1週間（月曜日から日曜日）のうち最低2日は休日でなければならない
     */
    private Constraint ensureWeeklyRestDays(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee,
                         sa -> getWeekStart(sa.getShiftDate()), // 週の開始日（月曜日）でグループ化
                         ConstraintCollectors.toSet(ShiftAssignmentPlanningEntity::getShiftDate))
                .filter((emp, weekStart, workDays) -> workDays.size() > 5) // 週6日以上勤務の場合
                .penalize(HardSoftScore.ONE_HARD, 
                          (emp, weekStart, workDays) -> workDays.size() - 5) // 5日を超えた分をペナルティ
                .asConstraint("Ensure at least 2 rest days per week");
    }

    /**
     * 指定された日付の週の開始日（月曜日）を取得
     * ISO 8601標準に従い、週は月曜日から始まる
     */
    private static LocalDate getWeekStart(LocalDate date) {
        // DayOfWeek.MONDAY = 1, SUNDAY = 7
        int dayOfWeek = date.getDayOfWeek().getValue();
        // 月曜日からの日数を計算して、その週の月曜日を求める
        return date.minusDays(dayOfWeek - 1);
    }

    /**
     * 計算月全体の人員過不足の分散を最小化する制約
     * 各日の需要と実際の配置人員の差の分散を抑えることで、
     * 月全体での人員配置の均等化を図る
     */
    private Constraint minimizeStaffingVariance(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.equal(RegisterDemandQuarter::getSlotTime, sa -> sa.getStartAt().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalTime()),
                        Joiners.filtering((demand, sa) -> sa.getAssignedEmployee() != null))
                .groupBy((demand, sa) -> demand.getDemandDate(), 
                         (demand, sa) -> demand.getSlotTime(),
                         (demand, sa) -> demand.getRequiredUnits(),
                         ConstraintCollectors.countBi())
                .groupBy((date, slot, required, assigned) -> 
                         YearMonth.from(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()), // 月でグループ化
                         ConstraintCollectors.toList((date, slot, required, assigned) -> 
                                 Math.abs(assigned - required))) // 過不足の絶対値をリスト化
                .penalize(HardSoftScore.ofSoft(50), 
                          (month, staffingDifferences) -> calculateVariancePenalty(staffingDifferences))
                .asConstraint("Minimize monthly staffing variance");
    }

    /**
     * 人員過不足リストの分散を計算してペナルティ値を返す
     * 分散が大きいほど高いペナルティを課す
     */
    private static int calculateVariancePenalty(List<Integer> staffingDifferences) {
        if (staffingDifferences.isEmpty()) return 0;
        
        // 平均値を計算
        double average = staffingDifferences.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
        
        // 分散を計算
        double variance = staffingDifferences.stream()
                .mapToDouble(diff -> {
                    double deviation = diff - average;
                    return deviation * deviation;
                })
                .average()
                .orElse(0.0);
        
        // 分散値にスケーリングファクターを適用してペナルティ値として返す
        // 分散が大きいほど高いペナルティ
        return (int) Math.round(variance * 10); // スケーリングファクター: 10
    }

    /**
     * レジ種別間の切り替わり頻度を最小化する制約
     * 同一従業員が一日の中で異なるレジ番号に頻繁に切り替わることを避ける
     */
    private Constraint minimizeRegisterSwitching(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, 
                         ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .penalize(HardSoftScore.ofSoft(800),
                          (emp, date, assignments) -> countRegisterSwitches(assignments))
                .asConstraint("Minimize register switching");
    }

    /**
     * 同一レジ種別での連続勤務を優先する制約
     * 従業員が同じレジで連続して勤務することを推奨
     */
    private Constraint preferConsistentRegisterAssignment(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee,
                         ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .reward(HardSoftScore.ofSoft(300),
                        (emp, date, assignments) -> countConsistentRegisterBlocks(assignments))
                .asConstraint("Prefer consistent register assignment");
    }

    /**
     * 一日の中でのレジ種別切り替え回数をカウント
     * 時間順にソートして、隣接するタイムスロット間でレジ番号が変わる回数を数える
     */
    private static int countRegisterSwitches(List<ShiftAssignmentPlanningEntity> assignments) {
        if (assignments.size() <= 1) return 0;
        
        assignments.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
        int switches = 0;
        
        for (int i = 1; i < assignments.size(); i++) {
            ShiftAssignmentPlanningEntity current = assignments.get(i);
            ShiftAssignmentPlanningEntity previous = assignments.get(i - 1);
            
            // 連続するタイムスロットでレジ番号が異なる場合、切り替わりとしてカウント
            if (current.getStartAt().equals(previous.getEndAt()) && 
                !current.getRegisterNo().equals(previous.getRegisterNo())) {
                switches++;
            }
        }
        
        return switches;
    }

    /**
     * 同一レジでの連続勤務ブロック数をカウント
     * より多くの連続ブロックを持つことで一貫性を評価
     */
    private static int countConsistentRegisterBlocks(List<ShiftAssignmentPlanningEntity> assignments) {
        if (assignments.isEmpty()) return 0;
        
        assignments.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
        int blocks = 0;
        Integer currentRegister = null;
        boolean inBlock = false;
        
        for (int i = 0; i < assignments.size(); i++) {
            ShiftAssignmentPlanningEntity current = assignments.get(i);
            
            if (i == 0) {
                currentRegister = current.getRegisterNo();
                inBlock = true;
            } else {
                ShiftAssignmentPlanningEntity previous = assignments.get(i - 1);
                
                // 連続するタイムスロットかつ同じレジの場合
                if (current.getStartAt().equals(previous.getEndAt()) && 
                    current.getRegisterNo().equals(currentRegister)) {
                    // 現在のブロックを継続
                    inBlock = true;
                } else {
                    // ブロック終了
                    if (inBlock) {
                        blocks++;
                    }
                    currentRegister = current.getRegisterNo();
                    inBlock = true;
                }
            }
        }
        
        // 最後のブロックをカウント
        if (inBlock) {
            blocks++;
        }
        
        return blocks;
    }
}


