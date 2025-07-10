package io.github.riemr.shift.domain.model;

import jakarta.persistence.*;
import lombok.Data;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "shift_assignment", uniqueConstraints = @UniqueConstraint(columnNames = { "store_code", "employee_code",
        "start_at" }))
@PlanningEntity
public class ShiftAssignment {
    @EmbeddedId
    private ShiftAssignmentId id;

    /* ---------------- JPA / FK ---------------- */
    @MapsId("employeeCode")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_code")
    @PlanningVariable(valueRangeProviderRefs = { "employeeRange" })
    private Employee employee;

    @MapsId("storeCode")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_code")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "store_code", referencedColumnName = "store_code", insertable = false, updatable = false),
            @JoinColumn(name = "register_no", referencedColumnName = "register_no")
    })
    @PlanningVariable(valueRangeProviderRefs = { "registerRange" })
    private Register register;

    /* ---------------- Other columns ---------------- */
    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy = "auto";

    /* convenience getters for OptaPlanner */
    public LocalDateTime getStartAt() {
        return id.getStartAt();
    }

    public String getStoreCode() {
        return id.getStoreCode();
    }
}