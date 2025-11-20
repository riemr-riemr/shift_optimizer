package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.infrastructure.persistence.entity.WorkDemandQuarter;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartmentSkill;
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
import java.util.Comparator;
import java.util.List;

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
            // Hard constraints (ASSIGNMENT relevant only)
            forbidDepartmentLowSkillForAssignment(factory),
            employeeNotDoubleBooked(factory),

            // Soft constraints (ASSIGNMENT only)
            registerDemandBalanceForAssignment(factory),
            registerDemandShortageWhenNoneForAssignment(factory),
            workDemandBalanceForAssignment(factory),
            workDemandShortageWhenNoneForAssignment(factory),
            preferHigherSkillLevelForAssignment(factory),
            preferDepartmentHigherSkillForAssignment(factory),
            
            balanceWorkload(factory),
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
    // ===================== ASSIGNMENT 専用制約 =====================

    private Constraint forbidDepartmentLowSkillForAssignment(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK
                        && (sa.getStage() == null || "ASSIGNMENT".equals(sa.getStage())))
                .join(EmployeeDepartmentSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeDepartmentSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getDepartmentCode, EmployeeDepartmentSkill::getDepartmentCode))
                .filter((sa, skill) -> skill.getSkillLevel() != null && (skill.getSkillLevel() == 0 || skill.getSkillLevel() == 1))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Forbidden department assignment (skill 0/1)");
    }

    /**
     * 部門作業の高スキル従業員優先制約（ソフト制約・ペナルティ方式）
     * スキルレベルが低い従業員を部門作業に配置することにペナルティを課す
     * スキルレベル2以上の従業員に対して、レベルが低いほど高いペナルティ
     * 
     * @param f 制約ファクトリ
     * @return 部門低スキル配置ペナルティ制約
     */
    private Constraint preferDepartmentHigherSkillForAssignment(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK
                        && (sa.getStage() == null || "ASSIGNMENT".equals(sa.getStage())))
                .join(EmployeeDepartmentSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeDepartmentSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getDepartmentCode, EmployeeDepartmentSkill::getDepartmentCode))
                .filter((sa, skill) -> skill.getSkillLevel() != null && skill.getSkillLevel() >= 2)
                .penalize(HardSoftScore.ofSoft(10), (sa, skill) -> {
                    // 最高部門スキルレベル(仮に4とする)からの差分をペナルティとする
                    int maxDeptSkillLevel = 4;
                    return Math.max(0, maxDeptSkillLevel - skill.getSkillLevel());
                })
                .asConstraint("Penalize lower department skill assignment");
    }

    /**
     * レジ需要充足制約（ソフト制約）
     * 各時間帯のレジ需要に対する人員配置の過不足を最小化
     * 人員不足は重いペナルティ、人員過多は軽いペナルティを課す
     * 
     * @param f 制約ファクトリ
     * @return レジ需要バランス制約
     */
    private Constraint registerDemandBalanceForAssignment(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.equal(RegisterDemandQuarter::getSlotTime,
                                sa -> sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime()),
                        Joiners.filtering((demand, sa) -> sa.getAssignedEmployee() != null && sa.getWorkKind() == WorkKind.REGISTER_OP
                                && (sa.getStage() == null || "ASSIGNMENT".equals(sa.getStage()))))
                .groupBy((demand, sa) -> demand, ConstraintCollectors.countBi())
                .penalize(HardSoftScore.ofSoft(10),
                        (demand, assigned) -> Math.abs(assigned - demand.getRequiredUnits()))
                .asConstraint("Register demand balance");
    }

    private Constraint registerDemandShortageWhenNoneForAssignment(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .ifNotExists(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.equal(RegisterDemandQuarter::getSlotTime,
                                sa -> sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime()),
                        Joiners.filtering((demand, sa) -> sa.getAssignedEmployee() != null && sa.getWorkKind() == WorkKind.REGISTER_OP))
                .penalize(HardSoftScore.ofSoft(1000), RegisterDemandQuarter::getRequiredUnits)
                .asConstraint("Register demand shortage (no assignment)");
    }

    /**
     * 部門作業需要充足制約（ソフト制約）
     * 各時間帯の部門作業需要に対する人員配置の過不足を最小化
     * レジ需要と同様に不足と過多でペナルティ重み付けを変える
     * 
     * @param f 制約ファクトリ
     * @return 部門作業需要バランス制約
     */
    private Constraint workDemandBalanceForAssignment(ConstraintFactory f) {
        return f.forEach(WorkDemandQuarter.class)
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(WorkDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.filtering((d, sa) -> sa.getAssignedEmployee() != null
                                && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK
                                && (sa.getStage() == null || "ASSIGNMENT".equals(sa.getStage()))
                                && d.getDepartmentCode().equals(sa.getDepartmentCode())
                                && d.getSlotTime().equals(sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime())
                        ))
                .groupBy((d, sa) -> d, ConstraintCollectors.countBi())
                .penalize(HardSoftScore.ofSoft(10), (d, assigned) -> Math.abs(assigned - d.getRequiredUnits()))
                .asConstraint("Work demand balance");
    }

    private Constraint workDemandShortageWhenNoneForAssignment(ConstraintFactory f) {
        return f.forEach(WorkDemandQuarter.class)
                .ifNotExists(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(WorkDemandQuarter::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.filtering((d, sa) -> sa.getAssignedEmployee() != null
                                && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK
                                && d.getDepartmentCode().equals(sa.getDepartmentCode())
                                && d.getSlotTime().equals(sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime())
                        ))
                .penalize(HardSoftScore.ofSoft(50), WorkDemandQuarter::getRequiredUnits)
                .asConstraint("Work demand shortage (no assignment)");
    }

    /**
     * レジ技能の高い従業員を優先（ASSIGNMENT専用）。
     */
    private Constraint preferHigherSkillLevelForAssignment(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null
                        && (sa.getStage() == null || "ASSIGNMENT".equals(sa.getStage())))
                .join(EmployeeRegisterSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeRegisterSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getRegisterNo, EmployeeRegisterSkill::getRegisterNo))
                .filter((sa, skill) -> skill.getSkillLevel() != null && skill.getSkillLevel() >= 2 && skill.getSkillLevel() <= 4)
                .penalize(HardSoftScore.ofSoft(10), (sa, skill) -> {
                    int maxSkillLevel = 4;
                    return (maxSkillLevel - skill.getSkillLevel()) * 200;
                })
                .asConstraint("Penalize lower skill level assignment (ASSIGNMENT)");
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
     * 休憩を勤務ブロックの中央付近に配置することを小さく誘導（ソフト制約）。
     * 6時間超勤務日のみ評価。休憩は60分以上の連続ギャップを休憩とみなす。
     */
    private Constraint preferCenteredBreak(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee,
                         ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .filter((emp, date, list) -> totalWorkedMinutes(list) > 360 && hasBreakAtLeast60(list))
                .penalize(HardSoftScore.ofSoft(1), (emp, date, list) -> {
                    list.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
                    var first = list.get(0).getStartAt().toInstant();
                    var last = list.get(list.size() - 1).getEndAt().toInstant();
                    long blockMid = first.plusMillis(java.time.Duration.between(first, last).toMillis() / 2).toEpochMilli();

                    // 60分以上の最初のギャップを休憩とみなす（複数ある場合の簡易実装）
                    long breakMid = blockMid;
                    for (int i = 1; i < list.size(); i++) {
                        var prevEnd = list.get(i - 1).getEndAt().toInstant();
                        var curStart = list.get(i).getStartAt().toInstant();
                        long gap = java.time.Duration.between(prevEnd, curStart).toMinutes();
                        if (gap >= 60) {
                            breakMid = prevEnd.plusMillis(java.time.Duration.between(prevEnd, curStart).toMillis() / 2).toEpochMilli();
                            break;
                        }
                    }
                    long diffMin = Math.abs((blockMid - breakMid) / (1000 * 60));
                    // 15分単位でペナルティ（小さめの重み）
                    return (int) Math.max(0, diffMin / 15);
                })
                .asConstraint("Prefer centered 60m break (soft, small)");
    }

    private static int totalWorkedMinutes(List<ShiftAssignmentPlanningEntity> list) {
        return list.stream().mapToInt(ShiftAssignmentPlanningEntity::getWorkMinutes).sum();
    }

    private static boolean hasBreakAtLeast60(List<ShiftAssignmentPlanningEntity> list) {
        if (list.size() <= 1) return false;
        list.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
        for (int i = 1; i < list.size(); i++) {
            long gap = java.time.Duration.between(
                    list.get(i - 1).getEndAt().toInstant(),
                    list.get(i).getStartAt().toInstant()).toMinutes();
            if (gap >= 60) return true;
        }
        return false;
    }

    // BreakAssignment を利用した重複チェックは無効化（エンティティ参照を排除）

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
                .filter(sa -> sa.getAssignedEmployee() != null
                        && sa.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.REGISTER_OP
                        && sa.getRegisterNo() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee, 
                         ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .penalize(HardSoftScore.ONE_SOFT,
                          (emp, date, assignments) -> countRegisterSwitches(assignments))
                .asConstraint("Minimize register switching");
    }

    /**
     * レジ一貫性優先制約（ソフト制約・ペナルティ方式）
     * 同一レジ種別での連続勤務ブロックが少ないことにペナルティを課す
     * 従業員が頻繁にレジを切り替えることを抑制
     * 業務精度向上と従業員の作業効率向上のための制約
     * 
     * @param f 制約ファクトリ
     * @return レジ不一貫性ペナルティ制約
     */
    private Constraint preferConsistentRegisterAssignment(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null
                        && sa.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.REGISTER_OP
                        && sa.getRegisterNo() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee,
                         ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .penalize(HardSoftScore.ONE_SOFT,
                        (emp, date, assignments) -> {
                            // 理想的なブロック数（1ブロック）からの差分をペナルティとする
                            int actualBlocks = countConsistentRegisterBlocks(assignments);
                            int idealBlocks = 1; // 理想は1つのまとまったブロック
                            return Math.max(0, actualBlocks - idealBlocks);
                        })
                .asConstraint("Penalize register assignment fragmentation");
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
            Integer curReg = current.getRegisterNo();
            Integer prevReg = previous.getRegisterNo();
            if (curReg != null && prevReg != null &&
                current.getStartAt().equals(previous.getEndAt()) && 
                !curReg.equals(prevReg)) {
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
    
}
