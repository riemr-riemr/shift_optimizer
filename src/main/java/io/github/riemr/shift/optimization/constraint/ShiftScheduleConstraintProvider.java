package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartmentSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftAssignment;
import io.github.riemr.shift.optimization.entity.RegisterDemandSlot;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.entity.WorkDemandSlot;
import io.github.riemr.shift.optimization.entity.WorkKind;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

import java.util.Comparator;
import java.util.Date;
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
            forbidMultipleRegistersSameSlot(factory),
            lunchBreakForLongShifts(factory),
            forbidAssignmentNearShiftBoundaries(factory),

            // Soft constraints (ASSIGNMENT only)
            penalizeUnassignedSlotForAssignment(factory),
            registerDemandBalanceForAssignment(factory),
            registerDemandShortageWhenNoneForAssignment(factory),
            workDemandBalanceForAssignment(factory),
            workDemandShortageWhenNoneForAssignment(factory),
            preferHigherSkillLevelForAssignment(factory),
            preferDepartmentHigherSkillForAssignment(factory),
            
            balanceWorkload(factory),
            minimizeRegisterSwitching(factory),
            preferConsistentRegisterAssignment(factory),
            preferConsistentDepartmentAssignment(factory)
        };
    }

    /**
     * 休憩（60分）は、同一従業員の当日の連続勤務（最初の開始〜最後の終了）が
     * 6時間(=360分)以上の場合のみ必須。
     * 6時間未満の勤務には休憩を要求しない。
     *
     * 現行モデルでは休憩を個別タスクとしては扱っていないため、
     * 当日内の割り当てスロット間に60分以上のギャップが存在するかで代替判定する。
     * ギャップが無い場合はハード違反とする。
     */
    private Constraint lunchBreakForLongShifts(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee,
                        ShiftAssignmentPlanningEntity::getShiftDate,
                        ConstraintCollectors.toList())
                .join(ShiftAssignment.class,
                        Joiners.equal((emp, date, list) -> emp.getEmployeeCode(), ShiftAssignment::getEmployeeCode),
                        Joiners.equal((emp, date, list) -> date,
                                shift -> shift.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()))
                .filter((emp, date, assignments, shift) -> {
                    if (shift.getStartAt() == null || shift.getEndAt() == null) return false;
                    long shiftMinutes = (shift.getEndAt().getTime() - shift.getStartAt().getTime()) / (1000 * 60);
                    if (shiftMinutes < 360) return false; // 6時間未満は要求しない
                    return !hasGapWithinWindow(assignments, shift.getStartAt(), shift.getEndAt(), 120, 60);
                })
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Missing 60min break within 2h buffer when daily work >= 6h");
    }

    private static boolean hasGapWithinWindow(List<ShiftAssignmentPlanningEntity> assignments,
                                              Date shiftStart,
                                              Date shiftEnd,
                                              int bufferMinutes,
                                              int gapMinutes) {
        if (assignments == null || assignments.size() <= 1) return false;
        if (shiftStart == null || shiftEnd == null) return false;
        long bufferMs = bufferMinutes * 60_000L;
        Date minStart = new Date(shiftStart.getTime() + bufferMs);
        Date maxEnd = new Date(shiftEnd.getTime() - bufferMs);
        if (!minStart.before(maxEnd)) return false;
        assignments.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
        long gapMs = gapMinutes * 60_000L;
        for (int i = 1; i < assignments.size(); i++) {
            Date prevEnd = assignments.get(i - 1).getEndAt();
            Date curStart = assignments.get(i).getStartAt();
            if (prevEnd == null || curStart == null) continue;
            if (curStart.getTime() - prevEnd.getTime() < gapMs) continue;
            Date breakStart = prevEnd;
            Date breakEnd = new Date(prevEnd.getTime() + gapMs);
            if (!breakStart.before(minStart) && !breakEnd.after(maxEnd)) {
                return true;
            }
        }
        return false;
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
                        && (sa.getStage() == null || sa.getStage().startsWith("ASSIGNMENT")))
                .join(EmployeeDepartmentSkill.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), EmployeeDepartmentSkill::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getDepartmentCode, EmployeeDepartmentSkill::getDepartmentCode))
                .filter((sa, skill) -> skill.getSkillLevel() != null && (skill.getSkillLevel() == 0 || skill.getSkillLevel() == 1))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Forbidden department assignment (skill 0/1)");
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
                Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate))
                .filter((a, b) -> a.getAssignedEmployee() != null && b.getAssignedEmployee() != null)
                .filter((a, b) -> a.getAssignedEmployee().getEmployeeCode().equals(b.getAssignedEmployee().getEmployeeCode()))
                .filter((a, b) -> overlaps(a, b))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Employee overlapping assignments");
    }

    /**
     * 同一従業員・同一スロットで複数のレジ割当が発生することを禁止する（ハード制約）。
     */
    private Constraint forbidMultipleRegistersSameSlot(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null
                        && sa.getWorkKind() == WorkKind.REGISTER_OP
                        && (sa.getStage() == null || sa.getStage().startsWith("ASSIGNMENT")))
                .groupBy(sa -> sa.getAssignedEmployee().getEmployeeCode(),
                        ShiftAssignmentPlanningEntity::getShiftDate,
                        ShiftAssignmentPlanningEntity::getStartAt,
                        ConstraintCollectors.count())
                .filter((emp, date, startAt, cnt) -> cnt != null && cnt > 1)
                .penalize(HardSoftScore.ONE_HARD, (emp, date, startAt, cnt) -> cnt - 1)
                .asConstraint("Forbid multiple registers in same slot");
    }

    private static boolean overlaps(ShiftAssignmentPlanningEntity a, ShiftAssignmentPlanningEntity b) {
        if (a.getStartAt() == null || a.getEndAt() == null || b.getStartAt() == null || b.getEndAt() == null) {
            return false;
        }
        // 境界が接している場合は重複ではない（9:00-9:15と9:15-9:30は重複しない）
        // 真の重複は時間が内部的に交わる場合のみ
        return a.getStartAt().before(b.getEndAt()) && b.getStartAt().before(a.getEndAt()) 
            && !a.getEndAt().equals(b.getStartAt()) && !b.getEndAt().equals(a.getStartAt());
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
                        && (sa.getStage() == null || sa.getStage().startsWith("ASSIGNMENT")))
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
        return f.forEach(RegisterDemandSlot.class)
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(RegisterDemandSlot::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.equal(RegisterDemandSlot::getStoreCode, ShiftAssignmentPlanningEntity::getStoreCode),
                        Joiners.equal(RegisterDemandSlot::getSlotTime,
                                sa -> sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime()),
                        Joiners.equal(RegisterDemandSlot::getRegisterNo, ShiftAssignmentPlanningEntity::getRegisterNo),
                        Joiners.filtering((demand, sa) -> sa.getAssignedEmployee() != null
                                && sa.getWorkKind() == WorkKind.REGISTER_OP
                                && (sa.getStage() == null || sa.getStage().startsWith("ASSIGNMENT"))
                        ))
                .groupBy((demand, sa) -> demand, ConstraintCollectors.countBi())
                // レジ需要を優先（不足: ×20、過多: ×1、基底重み 200）
                .penalize(HardSoftScore.ofSoft(200),
                        (demand, assigned) -> {
                            int required = demand.getRequiredUnits() == null ? 0 : Math.max(0, demand.getRequiredUnits());
                            int diff = assigned - required;
                            if (diff < 0) {
                                // 需要不足：より重く罰する
                                return (-diff) * 20;
                            } else if (diff > 0) {
                                // 需要過多：軽いペナルティ（線形）
                                return diff;
                            }
                            return 0;
                        })
                .asConstraint("Register demand balance");
    }

    private Constraint registerDemandShortageWhenNoneForAssignment(ConstraintFactory f) {
        return f.forEach(RegisterDemandSlot.class)
                .ifNotExists(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(RegisterDemandSlot::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.equal(RegisterDemandSlot::getStoreCode, ShiftAssignmentPlanningEntity::getStoreCode),
                        Joiners.equal(RegisterDemandSlot::getSlotTime,
                                sa -> sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime()),
                        Joiners.equal(RegisterDemandSlot::getRegisterNo, ShiftAssignmentPlanningEntity::getRegisterNo),
                        Joiners.filtering((demand, sa) -> sa.getAssignedEmployee() != null
                                && sa.getWorkKind() == WorkKind.REGISTER_OP
                                && (sa.getStage() == null || sa.getStage().startsWith("ASSIGNMENT"))
                        ))
                // 無配置（完全未割当）の場合はさらに強いペナルティ
                .penalize(HardSoftScore.ofSoft(400), d -> d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits()))
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
        return f.forEach(WorkDemandSlot.class)
                .join(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(WorkDemandSlot::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.equal(WorkDemandSlot::getStoreCode, ShiftAssignmentPlanningEntity::getStoreCode),
                        Joiners.filtering((d, sa) -> sa.getAssignedEmployee() != null
                                && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK
                                && (sa.getStage() == null || sa.getStage().startsWith("ASSIGNMENT"))
                                && d.getDepartmentCode().equals(sa.getDepartmentCode())
                                && d.getSlotTime().equals(sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime())
                        ))
                .groupBy((d, sa) -> d, ConstraintCollectors.countBi())
                // 部門作業はレジより優先度を下げる（不足: ×5、過多: ×1、基底重み 10）
                .penalize(HardSoftScore.ofSoft(10), (d, assigned) -> {
                    int required = d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits());
                    int diff = assigned - required;
                    if (diff < 0) {
                        return (-diff) * 5;
                    } else if (diff > 0) {
                        // 需要過多：軽いペナルティ（線形）
                        return diff;
                    }
                    return 0;
                })
                .asConstraint("Work demand balance");
    }

    private Constraint workDemandShortageWhenNoneForAssignment(ConstraintFactory f) {
        return f.forEach(WorkDemandSlot.class)
                .ifNotExists(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(WorkDemandSlot::getDemandDate, ShiftAssignmentPlanningEntity::getShiftDate),
                        Joiners.filtering((d, sa) -> sa.getAssignedEmployee() != null
                                && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK
                                && d.getDepartmentCode().equals(sa.getDepartmentCode())
                                && d.getSlotTime().equals(sa.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime())
                        ))
                .penalize(HardSoftScore.ofSoft(50), d -> d.getRequiredUnits() == null ? 0 : Math.max(0, d.getRequiredUnits()))
                .asConstraint("Work demand shortage (no assignment)");
    }

    /**
     * スロット未割当そのものに強いソフトペナルティを課す。
     * 需要ベースの不足ペナルティに加えて、各スロットのNULL割当を直接的に抑制する。
     */
    private Constraint penalizeUnassignedSlotForAssignment(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() == null)
                // 1スロット未割当につき極めて強いペナルティ
                .penalize(HardSoftScore.ofSoft(100000))
                .asConstraint("Penalize unassigned slot (ASSIGNMENT)");
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
                    return maxSkillLevel - skill.getSkillLevel();
                })
                .asConstraint("Penalize lower skill level assignment (ASSIGNMENT)");
    }

    // 休憩関連の制約は現在未使用（性能向上のため削除）

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
                .penalize(HardSoftScore.ofSoft(1), (emp, cnt) -> {
                    // 平均から離れるほど高ペナルティ（従業員間の公平性を促進）
                    // 基準値を8時間分(32スロット)として設定
                    int target = 32; // 8時間 * 4スロット/時間
                    int deviation = Math.abs(cnt.intValue() - target);
                    // 性能向上のため線形ペナルティに変更
                    return Math.min(deviation, 50); // キャップ付き線形ペナルティ
                })
                .asConstraint("Balance workload");
    }

    // getWeekStartメソッドは未使用のため削除

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
                .penalize(HardSoftScore.ofSoft(50),
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
                .penalize(HardSoftScore.ofSoft(50),
                        (emp, date, assignments) -> {
                            // 理想的なブロック数（1ブロック）からの差分をペナルティとする
                            int actualBlocks = countConsistentRegisterBlocks(assignments);
                            int idealBlocks = 1; // 理想は1つのまとまったブロック
                            return Math.max(0, actualBlocks - idealBlocks);
                        })
                .asConstraint("Penalize register assignment fragmentation");
    }

    /**
     * 部門作業の一貫性優先制約（ソフト制約・ペナルティ方式）
     * 同一部門での連続勤務ブロックが少ないことにペナルティを課す
     * 部門作業が細切れになることを抑制
     *
     * @param f 制約ファクトリ
     * @return 部門作業の不一貫性ペナルティ制約
     */
    private Constraint preferConsistentDepartmentAssignment(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null
                        && sa.getWorkKind() == WorkKind.DEPARTMENT_TASK
                        && sa.getDepartmentCode() != null)
                .groupBy(ShiftAssignmentPlanningEntity::getAssignedEmployee,
                         ShiftAssignmentPlanningEntity::getShiftDate,
                         ConstraintCollectors.toList())
                .penalize(HardSoftScore.ofSoft(30),
                        (emp, date, assignments) -> {
                            int actualBlocks = countConsistentDepartmentBlocks(assignments);
                            int idealBlocks = 1;
                            return Math.max(0, actualBlocks - idealBlocks);
                        })
                .asConstraint("Penalize department assignment fragmentation");
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

    /**
     * 同一部門での連続勤務ブロック数をカウント
     *
     * @param assignments 同一従業員・同一日の勤務割り当てリスト
     * @return 同一部門での連続勤務ブロック数
     */
    private static int countConsistentDepartmentBlocks(List<ShiftAssignmentPlanningEntity> assignments) {
        if (assignments.isEmpty()) return 0;

        assignments.sort(Comparator.comparing(ShiftAssignmentPlanningEntity::getStartAt));
        int blocks = 0;
        String currentDepartment = null;
        boolean inBlock = false;

        for (int i = 0; i < assignments.size(); i++) {
            ShiftAssignmentPlanningEntity current = assignments.get(i);
            if (current.getWorkKind() != WorkKind.DEPARTMENT_TASK) continue;
            if (i == 0) {
                currentDepartment = current.getDepartmentCode();
                inBlock = true;
            } else {
                ShiftAssignmentPlanningEntity previous = assignments.get(i - 1);
                if (current.getStartAt().equals(previous.getEndAt())
                        && current.getDepartmentCode().equals(currentDepartment)) {
                    inBlock = true;
                } else {
                    if (inBlock) {
                        blocks++;
                    }
                    currentDepartment = current.getDepartmentCode();
                    inBlock = true;
                }
            }
        }

        if (inBlock) {
            blocks++;
        }

        return blocks;
    }

    /**
     * 出勤開始直後・終了直前の1セルは割り当て禁止（ハード制約）
     * 1セルの長さは割当スロットの分解能（startAt〜endAt）を使用する。
     */
    private Constraint forbidAssignmentNearShiftBoundaries(ConstraintFactory f) {
        return f.forEach(ShiftAssignmentPlanningEntity.class)
                .filter(sa -> sa.getAssignedEmployee() != null
                        && (sa.getStage() == null || sa.getStage().startsWith("ASSIGNMENT")))
                .join(ShiftAssignment.class,
                        Joiners.equal(sa -> sa.getAssignedEmployee().getEmployeeCode(), ShiftAssignment::getEmployeeCode),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate,
                                shift -> shift.getStartAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()))
                .filter((sa, shift) -> {
                    if (sa.getStartAt() == null || sa.getEndAt() == null
                            || shift.getStartAt() == null || shift.getEndAt() == null) {
                        return false;
                    }
                    int slotMinutes = sa.getWorkMinutes();
                    if (slotMinutes <= 0) return false;
                    long slotMs = slotMinutes * 60_000L;
                    Date minStart = new Date(shift.getStartAt().getTime() + slotMs);
                    Date maxEnd = new Date(shift.getEndAt().getTime() - slotMs);
                    if (!minStart.before(maxEnd)) {
                        return true;
                    }
                    return sa.getStartAt().before(minStart) || sa.getEndAt().after(maxEnd);
                })
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Forbid assignment within 1-slot buffer at shift edges");
    }
    
}
