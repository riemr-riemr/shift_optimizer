package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

public class AttendanceGroupConstraint implements Serializable {
    private Long constraintId;
    private String storeCode;
    private String departmentCode;
    private String ruleType;
    private Integer minOnDuty;
    private Date createdAt;
    private Date updatedAt;

    public Long getConstraintId() {
        return constraintId;
    }

    public void setConstraintId(Long constraintId) {
        this.constraintId = constraintId;
    }

    public String getStoreCode() {
        return storeCode;
    }

    public void setStoreCode(String storeCode) {
        this.storeCode = storeCode == null ? null : storeCode.trim();
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode == null ? null : departmentCode.trim();
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType == null ? null : ruleType.trim();
    }

    public Integer getMinOnDuty() {
        return minOnDuty;
    }

    public void setMinOnDuty(Integer minOnDuty) {
        this.minOnDuty = minOnDuty;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
