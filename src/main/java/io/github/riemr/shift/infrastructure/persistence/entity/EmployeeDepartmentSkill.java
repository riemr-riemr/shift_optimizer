package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;

public class EmployeeDepartmentSkill implements Serializable {
    private String employeeCode;
    private String departmentCode;
    private Short skillLevel;

    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }

    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }

    public Short getSkillLevel() { return skillLevel; }
    public void setSkillLevel(Short skillLevel) { this.skillLevel = skillLevel; }
}

