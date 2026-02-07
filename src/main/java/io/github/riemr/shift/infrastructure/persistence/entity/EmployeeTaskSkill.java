package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;

public class EmployeeTaskSkill implements Serializable {
    private String employeeCode;
    private String storeCode;       // 追加: 店舗コード
    private String departmentCode;  // 追加: 部門コード
    private String taskCode;
    private Short skillLevel;

    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public Short getSkillLevel() { return skillLevel; }
    public void setSkillLevel(Short skillLevel) { this.skillLevel = skillLevel; }
}
