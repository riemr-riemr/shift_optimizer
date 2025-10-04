package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;

public class StoreDepartment implements Serializable {
    private String storeCode;
    private String departmentCode;
    private Integer displayOrder;
    private Boolean isActive;

    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }

    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}

