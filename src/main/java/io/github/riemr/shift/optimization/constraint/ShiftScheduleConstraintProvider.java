package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.infrastructure.persistence.entity.WorkDemandQuarter;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartmentSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.entity.WorkKind;
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

/**
 * シフト最適化の制約定義クラス
 * OptaPlannerの制約ベース最適化において、ハード制約（必須条件）とソフト制約（最適化目標）を定義
 * 
 * 制約の種類：
 * - ハード制約: 労働基準法、スキル要件、希望休日など（絶対に満たす必要がある条件）
 * - ソフト制約: 需要充足、負荷分散、効率性など（できるだけ満たしたい条件）
 */
public class ShiftScheduleConstraintProvider implements ConstraintProvider {

    /**
     * 全制約の定義メソッド
     * OptaPlannerが最適化時に評価する制約の配列を返す
     * 
     * @param factory 制約作成用のファクトリ
     * @return 全制約の配列（ハード制約とソフト制約を含む）
     */
    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            // Hard constraints
            forbidZeroSkill(factory),
            forbidDepartmentLowSkill(factory),
            employeeNotDoubleBooked(factory),
            maxWorkMinutesPerDay(factory),
            maxWorkDaysPerMonth(factory),
            maxConsecutiveDays(factory),
            forbidRequestedDayOff(factory),
            enforceLunchBreak(factory),
            
            ensureWeeklyRestDays(factory),
            forbidWorkOnOffDays(factory),
            enforceMandatoryWorkDays(factory),
            forbidWorkOutsideBaseHours(factory),

            // Soft constraints
            satisfyRegisterDemand(factory),
            satisfyWorkDemand(factory),
            preferHigherSkillLevel(factory),
            preferDepartmentHigherSkill(factory),
            preferWorkWithinBaseHours(factory),
            
            minimizeDailyWorkers(factory),
            balanceWorkload(factory),
            assignContiguously(factory),
            minimizeStaffingVariance(factory),
            minimizeRegisterSwitching(factory),
            preferConsistentRegisterAssignment(factory)
        };
    }

    /**
     * 部門作業の低スキル従業員配置禁止制約（ハード制約）
     * スキルレベル0（自動割当無効）または1（割当禁止）の従業員を部門作業に配置することを禁止
     * 
     * @param f 制約ファクトリ
     * @return 部門低スキル配置禁止制約
     */
    private Constraint forbidDepartmentLowSkill(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK)
                .join(EmployeeDepartmentSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeDepartmentSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getDepartmentCode, EmployeeDepartmentSkill::getDepartmentCode))
                .filter((sa, skill) -> skill.getSkillLevel() != null && (skill.getSkillLevel() == 0 || skill.getSkillLevel() == 1))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Forbidden department assignment (skill 0/1)");
    }

    /**
     * 部門作業の高スキル従業員優先制約（ソフト制約）
     * スキルレベルが高い従業員を部門作業に優先的に配置する
     * スキルレベル2以上の従業員に対して、レベルに応じた報酬を付与
     * 
     * @param f 制約ファクトリ
     * @return 部門高スキル優先制約
     */
    private Constraint preferDepartmentHigherSkill(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK)
                .join(EmployeeDepartmentSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeDepartmentSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getDepartmentCode, EmployeeDepartmentSkill::getDepartmentCode))
                .filter((sa, skill) -> skill.getSkillLevel() != null && skill.getSkillLevel() >= 2)
                .reward(HardSoftScore.ofSoft(180), (sa, skill) -> (skill.getSkillLevel() - 1) * 180)
                .asConstraint("Prefer higher department skill");
    }

    /**
     * レジ需要充足制約（ソフト制約）
     * 各時間帯のレジ需要に対する人員配置の過不足を最小化
     * 人員不足は重いペナルティ、人員過多は軽いペナルティを課す
     * 
     * @param f 制約ファクトリ
     * @return レジ需要バランス制約
     */
    private Constraint satisfyRegisterDemand(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.filtering((demand, sa) -> sa.getAssignedEmployee() != null && sa.getWorkKind() == WorkKind.REGISTER_OP))
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

    /**
     * 部門作業需要充足制約（ソフト制約）
     * 各時間帯の部門作業需要に対する人員配置の過不足を最小化
     * レジ需要と同様に不足と過多でペナルティ重み付けを変える
     * 
     * @param f 制約ファクトリ
     * @return 部門作業需要バランス制約
     */
    private Constraint satisfyWorkDemand(ConstraintFactory f) {
        return f.forEach(WorkDemandQuarter.class)
                .join(ShiftAssignmentPlanningEntity.class,
                    Joiners.equal(WorkDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                    Joiners.filtering((d, sa) -> sa.getAssignedEmployee() != null
                        && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK
                        && d.getDepartmentCode().equals(sa.getDepartmentCode())
                        && d.getSlotTime().equals(sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime())
                    ))
                .groupBy((d, sa) -> d, ConstraintCollectors.countBi())
                .penalize(HardSoftScore.ofSoft(900), (d, assigned) -> {
                    int shortage = d.getRequiredUnits() - assigned;
                    if (shortage > 0) return shortage * 100;
                    int over = assigned - d.getRequiredUnits();
                    return over * 50;
                })
                .asConstraint("Work demand balance");
    }

    /**
     * レジ低スキル従業員配置禁止制約（ハード制約）
     * スキルレベル0（自動割当無効）または1（割当禁止）の従業員をレジに配置することを禁止
     * 労働安全と業務品質確保のための必須制約
     * 
     * @param f 制約ファクトリ
     * @return レジ低スキル配置禁止制約
     */
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

    /**
     * 従業員重複配置禁止制約（ハード制約）
     * 同一従業員が同じ日の同じ時刻に複数の場所に配置されることを禁止
     * 物理的に不可能な配置を防ぐ基本的な制約
     * 
     * @param f 制約ファクトリ
     * @return 従業員重複配置禁止制約
     */
    private Constraint employeeNotDoubleBooked(ConstraintFactory f) {
        return f.forEachUniquePair(ShiftAssignmentPlanningEntity.class,
                Joiners.equal(ShiftAssignmentPlanningEntity::getAssignedEmployee),
                Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate),
                Joiners.equal(ShiftAssignmentPlanningEntity::getStartAt))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Employee double booked");
    }

    /**
     * 日次最大労働時間制約（ハード制約）
     * 各従業員の1日の労働時間が設定値（max_work_minutes_day）を超えることを禁止
     * 労働基準法遵守のための重要な制約（休憩時間を除く実労働時間で計算）
     * 
     * @param f 制約ファクトリ
     * @return 日次最大労働時間制約
     */
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

    /**
     * 月次最大勤務日数制約（ハード制約）
     * 各従業員の月間勤務日数が設定値（max_work_days_month）を超えることを禁止
     * 過労防止と労働時間管理のための制約
     * 
     * @param f 制約ファクトリ
     * @return 月次最大勤務日数制約
     */
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

    /**
     * 連続勤務日数制限制約（ハード制約）
     * 従業員が5日以上連続で勤務することを禁止
     * 疲労蓄積防止と労働基準法遵守のための制約
     * 
     * @param f 制約ファクトリ
     * @return 連続勤務日数制限制約
     */
    private Constraint maxConsecutiveDays(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee,
                         ConstraintCollectors.toSortedSet(ShiftAssignmentPlanningEntity::getShiftDate))
                .filter((emp, dates) -> hasRunLength(dates, 5))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("More than 4 consecutive days");
    }

    /**
     * 指定された日付セットに指定長以上の連続日があるかをチェック
     * 
     * @param dates 勤務日の集合
     * @param limit 連続日数の上限
     * @return 上限以上の連続日がある場合true
     */
    private static boolean hasRunLength(Set<LocalDate> dates, int limit) {
        LocalDate prev = null; int run = 0;
        for (LocalDate d : dates) {
            if (prev != null && prev.plusDays(1).equals(d)) {
                run++; if (run >= limit) return true;
            } else run = 1;
        }
        return false;
    }

    /**
     * 希望休日配置禁止制約（ハード制約）
     * 従業員が事前に申請した休暇日（request_kind='off'）への勤務配置を禁止
     * 従業員の希望を尊重し、労働者の権利を保護する制約
     * 
     * @param f 制約ファクトリ
     * @return 希望休日配置禁止制約
     */
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

    /**
     * 昼食休憩強制制約（ハード制約）
     * 6時間以上の連続勤務時に1時間以上の休憩時間を強制
     * 労働基準法第34条（休憩時間）の遵守のための制約
     * 
     * @param f 制約ファクトリ
     * @return 昼食休憩強制制約
     */
    private Constraint enforceLunchBreak(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .filter((emp, date, list) -> exceedsSixHoursWithoutBreak(list))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("No 1h break");
    }

    /**
     * 6時間以上の連続勤務で1時間以上の休憩がない勤務があるかをチェック
     * 労働基準法第34条の休憩時間規定に基づく判定
     * 
     * @param list 同一従業員・同一日の勤務割り当てリスト
     * @return 6時間以上連続勤務で適切な休憩がない場合true
     */
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

    /**
     * 日次勤務者数最小化制約（ソフト制約）
     * 各日の勤務者数を最小化して人件費を抑制
     * 効率的な人員配置を促進する経営効率化制約
     * 
     * @param f 制約ファクトリ
     * @return 日次勤務者数最小化制約
     */
    private Constraint minimizeDailyWorkers(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toSet(ShiftAssignmentPlanningEntity::getAssignedEmployee))
                .penalize(HardSoftScore.ofSoft(500),
                          (date, empSet) -> empSet.size())
                .asConstraint("Minimize daily workers");
    }

    /**
     * 労働負荷均等化制約（ソフト制約）
     * 従業員間の勤務時間数の偏りを最小化
     * 公平な労働分担と従業員満足度向上のための制約
     * 
     * @param f 制約ファクトリ
     * @return 労働負荷均等化制約
     */
    private Constraint balanceWorkload(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, ConstraintCollectors.count())
                .penalize(HardSoftScore.ofSoft(5), (emp, cnt) -> cnt.intValue() * cnt.intValue())
                .asConstraint("Balance workload");
    }

    /**
     * 数値リストの分散を計算してペナルティ値として返す（未使用メソッド）
     * 
     * @param counts 数値のリスト
     * @return 分散値に基づくペナルティ
     */
    private static int variancePenalty(List<Long> counts) {
        double avg = counts.stream().mapToLong(Long::longValue).average().orElse(0);
        double var = counts.stream().mapToDouble(c -> (c - avg) * (c - avg)).sum();
        return (int) Math.round(var);
    }

    /**
     * 連続勤務時間推奨制約（ソフト制約）
     * 同一従業員の1日の勤務時間を連続化し、細切れ勤務を回避
     * 業務効率向上と従業員の利便性向上のための制約
     * 
     * @param f 制約ファクトリ
     * @return 連続勤務時間推奨制約
     */
    private Constraint assignContiguously(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .penalize(HardSoftScore.ofSoft(1),
                          (emp, date, list) -> countGaps(list))
                .asConstraint("Fragmented blocks");
    }

    /**
     * 同一従業員の1日の勤務における時間的な隙間（ギャップ）の数をカウント
     * 連続していない勤務時間スロット間の隙間を数える
     * 
     * @param list 同一従業員・同一日の勤務割り当てリスト
     * @return 勤務時間の隙間数
     */
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
     * 
     * @param list 同一従業員・同一日の勤務割り当てリスト
     * @return 休憩時間を除いた実労働時間（分）
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
    // baseStart/baseEnd 時刻の存在チェックは曜日別設定に置換予定

    /**
     * 従業員の基本勤務時間からの乖離を最小化する
     * base_start_time/base_end_timeに近い時間帯での勤務を優先
     */
    // preferBaseWorkTimes は曜日別の週間設定へ置換予定（未実装）

    /**
     * 週休二日保証制約（ハード制約）
     * 各従業員について、1週間（月曜日から日曜日）のうち最低2日は休日でなければならない
     * 労働基準法の週休二日制遵守のための重要な制約
     * 
     * @param f 制約ファクトリ
     * @return 週休二日保証制約
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
     * 
     * @param date 基準となる日付
     * @return その日が含まれる週の月曜日
     */
    private static LocalDate getWeekStart(LocalDate date) {
        // DayOfWeek.MONDAY = 1, SUNDAY = 7
        int dayOfWeek = date.getDayOfWeek().getValue();
        // 月曜日からの日数を計算して、その週の月曜日を求める
        return date.minusDays(dayOfWeek - 1);
    }

    /**
     * 月次人員配置分散最小化制約（ソフト制約）
     * 計算月全体の人員過不足の分散を最小化する制約
     * 各日の需要と実際の配置人員の差の分散を抑えることで、月全体での人員配置の均等化を図る
     * 
     * @param f 制約ファクトリ
     * @return 月次人員配置分散最小化制約
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
     * 
     * @param staffingDifferences 各時間帯の人員過不足の絶対値リスト
     * @return 分散に基づくペナルティ値
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
     * レジ切り替え最小化制約（ソフト制約）
     * レジ種別間の切り替わり頻度を最小化する制約
     * 同一従業員が一日の中で異なるレジ番号に頻繁に切り替わることを避ける
     * 業務効率向上と従業員の作業負荷軽減のための制約
     * 
     * @param f 制約ファクトリ
     * @return レジ切り替え最小化制約
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
     * レジ一貫性優先制約（ソフト制約）
     * 同一レジ種別での連続勤務を優先する制約
     * 従業員が同じレジで連続して勤務することを推奨
     * 業務精度向上と従業員の作業効率向上のための制約
     * 
     * @param f 制約ファクトリ
     * @return レジ一貫性優先制約
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
     * 
     * @param assignments 同一従業員・同一日の勤務割り当てリスト
     * @return レジ種別切り替え回数
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
     * 
     * @param assignments 同一従業員・同一日の勤務割り当てリスト
     * @return 同一レジでの連続勤務ブロック数
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

    /**
     * 曜日別勤務設定でOFFが設定されている日の勤務を禁止するハード制約
     * employee_weekly_preferenceでwork_style='OFF'の曜日は該当従業員は休日
     */
    private Constraint forbidWorkOnOffDays(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .join(EmployeeWeeklyPreference.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeWeeklyPreference::getEmployeeCode),
                        Joiners.equal(sa -> sa.getShiftDate().getDayOfWeek().getValue(), 
                                     pref -> pref.getDayOfWeek().intValue()))
                .filter((sa, pref) -> {
                    boolean isOffDay = "OFF".equalsIgnoreCase(pref.getWorkStyle());
                    if (isOffDay) {
                        System.out.println("CONSTRAINT VIOLATION: Employee " + sa.getAssignedEmployee().getEmployeeCode() + 
                                         " assigned on OFF day: " + sa.getShiftDate() + " (" + sa.getShiftDate().getDayOfWeek() + ")");
                    }
                    return isOffDay;
                })
                .penalize(HardSoftScore.of(10000, 0))
                .asConstraint("Assigned on weekly OFF day");
    }

    /**
     * 曜日別勤務設定でMANDATORYが設定されている日は必ず出勤させるソフト制約
     * employee_weekly_preferenceでwork_style='MANDATORY'の曜日は該当従業員は必須出勤
     * 該当曜日に勤務していない場合にペナルティを課す
     */
    private Constraint enforceMandatoryWorkDays(ConstraintFactory f) {
        return f.forEach(EmployeeWeeklyPreference.class)
                .filter(pref -> "MANDATORY".equalsIgnoreCase(pref.getWorkStyle()))
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(EmployeeWeeklyPreference::getEmployeeCode, 
                                     sa -> sa.getAssignedEmployee() != null ? sa.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.equal(pref -> pref.getDayOfWeek().intValue(),
                                     sa -> sa.getShiftDate().getDayOfWeek().getValue()))
                .groupBy((pref, sa) -> pref, 
                         (pref, sa) -> sa.getShiftDate(),
                         ConstraintCollectors.countBi())
                .filter((pref, date, assignmentCount) -> assignmentCount == 0) // 該当日に勤務がない場合
                .penalize(HardSoftScore.ofSoft(5000)) // 高いソフトペナルティ
                .asConstraint("Missing mandatory work day");
    }

    /**
     * 曜日別基本勤務時間外への勤務を禁止するハード制約
     * employee_weekly_preferenceのbase_start_time/base_end_time範囲外の勤務を禁止
     * OPTIONALまたはMANDATORYで時間設定がある場合のみ適用
     */
    private Constraint forbidWorkOutsideBaseHours(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .join(EmployeeWeeklyPreference.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeWeeklyPreference::getEmployeeCode),
                        Joiners.equal(sa -> sa.getShiftDate().getDayOfWeek().getValue(), 
                                     pref -> pref.getDayOfWeek().intValue()))
                .filter((sa, pref) -> {
                    // OFFの場合は他の制約で処理済み、時間設定がない場合は制約適用なし
                    if ("OFF".equalsIgnoreCase(pref.getWorkStyle()) || 
                        pref.getBaseStartTime() == null || pref.getBaseEndTime() == null) {
                        return false;
                    }
                    
                    // 勤務時間がbase_start_time/base_end_time範囲外かチェック
                    LocalTime shiftStartTime = sa.getStartAt().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalTime();
                    LocalTime shiftEndTime = sa.getEndAt().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalTime();
                    
                    LocalTime baseStart = pref.getBaseStartTime().toLocalTime();
                    LocalTime baseEnd = pref.getBaseEndTime().toLocalTime();
                    
                    // シフト時間が基本勤務時間範囲外の場合は違反
                    boolean isOutsideRange = shiftStartTime.isBefore(baseStart) || 
                                           shiftEndTime.isAfter(baseEnd);
                    
                    if (isOutsideRange) {
                        System.out.println("CONSTRAINT VIOLATION: Employee " + sa.getAssignedEmployee().getEmployeeCode() + 
                                         " assigned outside base hours on " + sa.getShiftDate().getDayOfWeek() + 
                                         ": shift(" + shiftStartTime + "-" + shiftEndTime + 
                                         ") vs base(" + baseStart + "-" + baseEnd + ")");
                    }
                    
                    return isOutsideRange;
                })
                .penalize(HardSoftScore.of(8000, 0)) // 高いハードペナルティ
                .asConstraint("Assigned outside base work hours");
    }

    /**
     * 基本勤務時間内での勤務を推奨するソフト制約
     * employee_weekly_preferenceのbase_start_time/base_end_time範囲内の勤務を優遇
     * 時間設定がある場合、その時間内での勤務にボーナスを与える
     */
    private Constraint preferWorkWithinBaseHours(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .join(EmployeeWeeklyPreference.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeWeeklyPreference::getEmployeeCode),
                        Joiners.equal(sa -> sa.getShiftDate().getDayOfWeek().getValue(), 
                                     pref -> pref.getDayOfWeek().intValue()))
                .filter((sa, pref) -> {
                    // OFFでなく、時間設定がある場合のみ適用
                    if ("OFF".equalsIgnoreCase(pref.getWorkStyle()) || 
                        pref.getBaseStartTime() == null || pref.getBaseEndTime() == null) {
                        return false;
                    }
                    
                    // 勤務時間がbase_start_time/base_end_time範囲内かチェック
                    LocalTime shiftStartTime = sa.getStartAt().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalTime();
                    LocalTime shiftEndTime = sa.getEndAt().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalTime();
                    
                    LocalTime baseStart = pref.getBaseStartTime().toLocalTime();
                    LocalTime baseEnd = pref.getBaseEndTime().toLocalTime();
                    
                    // シフト時間が基本勤務時間範囲内の場合は報酬対象
                    return !shiftStartTime.isBefore(baseStart) && !shiftEndTime.isAfter(baseEnd);
                })
                .reward(HardSoftScore.ofSoft(100)) // 基本時間内勤務への報酬
                .asConstraint("Prefer work within base hours");
    }
}
