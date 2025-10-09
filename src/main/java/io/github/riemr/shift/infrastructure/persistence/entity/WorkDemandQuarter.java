package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

public class WorkDemandQuarter implements Serializable {
    private Long demandId;
    private String storeCode;
    private String departmentCode;
    private Date demandDate;
    private java.time.LocalTime slotTime;
    private String taskCode; // nullable
    private Integer requiredUnits;
    private String groupId;

    public Long getDemandId() { return demandId; }
    public void setDemandId(Long demandId) { this.demandId = demandId; }

    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }

    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }

    public Date getDemandDate() { return demandDate; }
    public void setDemandDate(Date demandDate) { this.demandDate = demandDate; }

    public java.time.LocalTime getSlotTime() { return slotTime; }
    public void setSlotTime(java.time.LocalTime slotTime) { this.slotTime = slotTime; }

    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }

    public Integer getRequiredUnits() { return requiredUnits; }
    public void setRequiredUnits(Integer requiredUnits) { this.requiredUnits = requiredUnits; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
}
