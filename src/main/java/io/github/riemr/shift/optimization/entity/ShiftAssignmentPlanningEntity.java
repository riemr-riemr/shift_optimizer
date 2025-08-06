package io.github.riemr.shift.optimization.entity;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterAssignment;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

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

    @PlanningVariable(valueRangeProviderRefs = {"employeeRange"})
    private Employee assignedEmployee;

    public ShiftAssignmentPlanningEntity() {
    }

    public ShiftAssignmentPlanningEntity(RegisterAssignment origin) {
        this.origin = origin;
        this.assignedEmployee = null;
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
}

