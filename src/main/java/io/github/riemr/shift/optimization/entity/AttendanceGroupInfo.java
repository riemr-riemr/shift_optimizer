package io.github.riemr.shift.optimization.entity;

import java.util.Collections;
import java.util.Set;

public class AttendanceGroupInfo {
    private final Long constraintId;
    private final String storeCode;
    private final String departmentCode;
    private final AttendanceGroupRuleType ruleType;
    private final Integer minOnDuty;
    private final Set<String> memberEmployeeCodes;

    public AttendanceGroupInfo(Long constraintId,
                               String storeCode,
                               String departmentCode,
                               AttendanceGroupRuleType ruleType,
                               Integer minOnDuty,
                               Set<String> memberEmployeeCodes) {
        this.constraintId = constraintId;
        this.storeCode = storeCode;
        this.departmentCode = departmentCode;
        this.ruleType = ruleType;
        this.minOnDuty = minOnDuty;
        this.memberEmployeeCodes = memberEmployeeCodes == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(memberEmployeeCodes);
    }

    public Long getConstraintId() {
        return constraintId;
    }

    public String getStoreCode() {
        return storeCode;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public AttendanceGroupRuleType getRuleType() {
        return ruleType;
    }

    public Integer getMinOnDuty() {
        return minOnDuty;
    }

    public Set<String> getMemberEmployeeCodes() {
        return memberEmployeeCodes;
    }

    public int getMemberCount() {
        return memberEmployeeCodes.size();
    }

    public boolean hasMember(String employeeCode) {
        if (employeeCode == null) return false;
        return memberEmployeeCodes.contains(employeeCode);
    }
}
