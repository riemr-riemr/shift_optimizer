package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;

public class AttendanceGroupMember implements Serializable {
    private Long constraintId;
    private String employeeCode;

    public Long getConstraintId() {
        return constraintId;
    }

    public void setConstraintId(Long constraintId) {
        this.constraintId = constraintId;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public void setEmployeeCode(String employeeCode) {
        this.employeeCode = employeeCode == null ? null : employeeCode.trim();
    }
}
