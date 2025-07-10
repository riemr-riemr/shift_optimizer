package io.github.riemr.shift.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@Embeddable
public class ShiftAssignmentId implements Serializable {
    @Column(name = "store_code")
    private String storeCode;

    @Column(name = "employee_code")
    private String employeeCode;

    @Column(name = "start_at")
    private LocalDateTime startAt;
}