package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;

public class DepartmentMaster implements Serializable {
    private String departmentCode;
    private String departmentName;
    private Integer displayOrder;
    private Boolean isActive;
    private Boolean isRegister;

    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }

    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Boolean getIsRegister() { return isRegister; }
    public void setIsRegister(Boolean isRegister) { this.isRegister = isRegister; }
}
