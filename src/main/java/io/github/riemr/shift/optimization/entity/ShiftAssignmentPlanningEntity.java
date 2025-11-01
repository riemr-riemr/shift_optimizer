package io.github.riemr.shift.optimization.entity;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterAssignment;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

@PlanningEntity
@Getter
@Setter
@ToString
public class ShiftAssignmentPlanningEntity {

    @PlanningId
    private Long shiftId;

    private RegisterAssignment origin;

    // department-aware fields (for future extension)
    private String departmentCode; // e.g., REGISTER or other department
    private WorkKind workKind;     // REGISTER_OP or DEPARTMENT_TASK
    private String taskCode;       // used when workKind == DEPARTMENT_TASK

    // Optimization stage hint: ATTENDANCE or ASSIGNMENT
    private String stage;

    @PlanningVariable(valueRangeProviderRefs = {"availableEmployees"})
    private Employee assignedEmployee;

    // エンティティ毎に可用な従業員候補（ATTENDANCE/ASSIGNMENTでフィルタリング）
    private java.util.List<Employee> candidateEmployees = java.util.Collections.emptyList();

    public ShiftAssignmentPlanningEntity() {
    }

    public ShiftAssignmentPlanningEntity(RegisterAssignment origin) {
        this.origin = origin;
        this.assignedEmployee = null;
        this.workKind = WorkKind.REGISTER_OP;
    }

    public LocalDate getShiftDate() {
        return origin.getStartAt() == null ? null :
                origin.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public Integer getRegisterNo() {
        return origin.getRegisterNo();
    }

    public Date getStartAt() {
        return origin.getStartAt();
    }

    public Date getEndAt() {
        return origin.getEndAt();
    }

    public int getWorkMinutes() {
        if (origin.getStartAt() == null || origin.getEndAt() == null) {
            return 0;
        }
        long diff = origin.getEndAt().getTime() - origin.getStartAt().getTime();
        return (int) (diff / (1000 * 60));
    }

    public String getStoreCode() {
        return origin != null ? origin.getStoreCode() : null;
    }

    // エンティティ依存の従業員候補レンジ
    @ValueRangeProvider(id = "availableEmployees")
    public java.util.List<Employee> getAvailableEmployees() {
        return candidateEmployees == null ? java.util.Collections.emptyList() : candidateEmployees;
    }
}
