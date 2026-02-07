package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

public class TaskPlan implements Serializable {
    private Long planId;
    private String storeCode;
    private String departmentCode; // 追加: 部門コード (任意)
    private String taskCode;
    private Short dayOfWeek;
    private String scheduleType; // FIXED or FLEXIBLE
    private Date fixedStartTime;
    private Date fixedEndTime;
    private Date windowStartTime;
    private Date windowEndTime;
    private Integer requiredDurationMinutes;
    private Integer requiredStaffCount;
    private Integer lane; // 1-based lane index for grid display
    private Short mustBeContiguous;
    private Date effectiveFrom;
    private Date effectiveTo;
    private Integer priority;
    private String note;
    private Boolean active;

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public Short getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Short dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public Date getFixedStartTime() { return fixedStartTime; }
    public void setFixedStartTime(Date fixedStartTime) { this.fixedStartTime = fixedStartTime; }
    public Date getFixedEndTime() { return fixedEndTime; }
    public void setFixedEndTime(Date fixedEndTime) { this.fixedEndTime = fixedEndTime; }
    public Date getWindowStartTime() { return windowStartTime; }
    public void setWindowStartTime(Date windowStartTime) { this.windowStartTime = windowStartTime; }
    public Date getWindowEndTime() { return windowEndTime; }
    public void setWindowEndTime(Date windowEndTime) { this.windowEndTime = windowEndTime; }
    public Integer getRequiredDurationMinutes() { return requiredDurationMinutes; }
    public void setRequiredDurationMinutes(Integer requiredDurationMinutes) { this.requiredDurationMinutes = requiredDurationMinutes; }
    public Integer getRequiredStaffCount() { return requiredStaffCount; }
    public void setRequiredStaffCount(Integer requiredStaffCount) { this.requiredStaffCount = requiredStaffCount; }
    public Integer getLane() { return lane; }
    public void setLane(Integer lane) { this.lane = lane; }
    public Short getMustBeContiguous() { return mustBeContiguous; }
    public void setMustBeContiguous(Short mustBeContiguous) { this.mustBeContiguous = mustBeContiguous; }
    public Date getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Date effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Date getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(Date effectiveTo) { this.effectiveTo = effectiveTo; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
