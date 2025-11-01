package io.github.riemr.shift.optimization.nearby;

import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import org.optaplanner.core.impl.heuristic.selector.common.nearby.NearbyDistanceMeter;

import java.time.Duration;
import java.time.LocalDate;

public class AssignmentTimeNearbyDistanceMeter implements NearbyDistanceMeter<ShiftAssignmentPlanningEntity, ShiftAssignmentPlanningEntity> {

    @Override
    public double getNearbyDistance(ShiftAssignmentPlanningEntity origin, ShiftAssignmentPlanningEntity destination) {
        if (origin == null || destination == null || origin.getStartAt() == null || destination.getStartAt() == null) {
            return Double.MAX_VALUE;
        }

        // 同一日付のみを近傍とみなす
        LocalDate oDate = origin.getShiftDate();
        LocalDate dDate = destination.getShiftDate();
        if (oDate == null || dDate == null || !oDate.equals(dDate)) {
            return Double.MAX_VALUE;
        }

        // 同種別かつ同一リソースの近傍を強く優先
        boolean sameRegister = origin.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.REGISTER_OP
                && destination.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.REGISTER_OP
                && origin.getRegisterNo() != null && origin.getRegisterNo().equals(destination.getRegisterNo());

        boolean sameDeptTask = origin.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.DEPARTMENT_TASK
                && destination.getWorkKind() == io.github.riemr.shift.optimization.entity.WorkKind.DEPARTMENT_TASK
                && safeEq(origin.getDepartmentCode(), destination.getDepartmentCode())
                && safeEq(origin.getTaskCode(), destination.getTaskCode());

        long minutes = Math.abs(Duration.between(
                origin.getStartAt().toInstant(),
                destination.getStartAt().toInstant()).toMinutes());

        if (sameRegister || sameDeptTask) {
            return minutes; // 近いほど小さい
        }
        // 異なる資源は遠く扱う
        return minutes + 1_000_000d;
    }

    private static boolean safeEq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}

