package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

/**
 * Monthly recurring task plan.
 * Supports two patterns:
 *  - DOM: specific days of month (stored in monthly_task_plan_dom)
 *  - WOM: week-of-month x day-of-week (stored in monthly_task_plan_wom)
 */
public class MonthlyTaskPlan implements Serializable {
    private Long planId;
    private String storeCode;
    private String departmentCode;
    private String taskCode;
    private String scheduleType; // FIXED or FLEXIBLE
    private Date fixedStartTime;
    private Date fixedEndTime;
    private Date windowStartTime;
    private Date windowEndTime;
    private Integer requiredDurationMinutes;
    private Integer requiredStaffCount;
    private Integer lane; // visual lane
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

