package io.github.riemr.shift.application.dto;

import java.util.List;

public class AttendanceGroupConstraintView {
    private Long constraintId;
    private String storeCode;
    private String departmentCode;
    private String ruleType;
    private Integer minOnDuty;
    private List<String> memberLabels;

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
        this.storeCode = storeCode;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public Integer getMinOnDuty() {
        return minOnDuty;
    }

    public void setMinOnDuty(Integer minOnDuty) {
        this.minOnDuty = minOnDuty;
    }

    public List<String> getMemberLabels() {
        return memberLabels;
    }

    public void setMemberLabels(List<String> memberLabels) {
        this.memberLabels = memberLabels;
    }
}
