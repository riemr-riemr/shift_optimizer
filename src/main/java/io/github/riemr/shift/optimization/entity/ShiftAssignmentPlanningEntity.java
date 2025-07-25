package io.github.riemr.shift.optimization.entity;

import java.time.LocalDate;
import java.time.ZoneId;

import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

import io.github.riemr.shift.domain.Employee;
import io.github.riemr.shift.domain.ShiftAssignment;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@PlanningEntity
@Getter
@Setter
@ToString
public class ShiftAssignmentPlanningEntity {

    @PlanningId
    private Long shiftId;

    /**
     * 元の MyBatis 生成モデルを委譲保持。
     * 生成物の上書きを避けるため継承せず “has-a” にする。
     */
    private ShiftAssignment origin;

    /**
     * プランニング変数：誰をアサインするか。
     * Solver がこの値を動かして最適化します。
     */
    @PlanningVariable(valueRangeProviderRefs = {"employeeRange"})
    private Employee assignedEmployee;

    // OptaPlanner 用にデフォルトコンストラクタ必須
    public ShiftAssignmentPlanningEntity() {
    }

    public ShiftAssignmentPlanningEntity(ShiftAssignment origin) {
        this.origin = origin;
        // 既存割当が不要な場合は null。Solver が最適値を設定する。
        this.assignedEmployee = null;
    }

    // 便利メソッド – 制約条件で使いやすく
    public LocalDate getShiftDate() {
        return origin.getStartAt() == null ? null :
                origin.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public Integer getRegisterNo() {
        return origin.getRegisterNo();
    }
}