package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;

/**
 * 基本的な制約セット – 最小限の可行解を生成する。
 * <p>
 * Hard:
 * <ul>
 *   <li>各レジスターのシフトは必ず 1 人割り当て</li>
 *   <li>同じレジスター・同時刻に重複割当しない</li>
 *   <li>同一従業員が同日に複数レジスターに入らない</li>
 * </ul>
 * Soft:
 * <ul>
 *   <li>従業員ごとのシフト数を均等化</li>
 * </ul>
 */
public class ShiftScheduleConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                shiftMustBeAssigned(factory),
                registerNotDoubleBooked(factory),
                employeeMaxOneShiftPerDay(factory),
                balanceWorkload(factory)
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
                        Joiners.equal(ShiftAssignmentPlanningEntity::getRegisterNo))
                .penalize("Register double‐booked", HardSoftScore.ONE_HARD);
    }

    /** Hard 3: 従業員が同日に複数レジに入らない */
    private Constraint employeeMaxOneShiftPerDay(ConstraintFactory factory) {
        return factory.fromUniquePair(ShiftAssignmentPlanningEntity.class,
                        Joiners.equal(a -> a.getAssignedEmployee() == null ? null : a.getAssignedEmployee().getEmployeeCode()),
                        Joiners.equal(ShiftAssignmentPlanningEntity::getShiftDate))
                .penalize("Employee double shift same day", HardSoftScore.ONE_HARD);
    }

    /** Soft 1: シフト数を各従業員で均等化（分散を抑える） */
    private Constraint balanceWorkload(ConstraintFactory factory) {
        return factory.from(ShiftAssignmentPlanningEntity.class)
                .filter(a -> a.getAssignedEmployee() != null)
                .groupBy(a -> a.getAssignedEmployee().getEmployeeCode(), ConstraintCollectors.count())
                .penalize("Balance workload", HardSoftScore.ONE_SOFT, (employeeCode, count) -> count * count);
    }
}