package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

/**
 * Non-register task request entity.
 * Note: This is a hand-written POJO (not MyBatis Generator output).
 */
public class Task implements Serializable {
    private Long taskId;
    private String storeCode;
    private Date workDate; // date-only semantics
    private String name;
    private String description;
    private String scheduleType; // FIXED | FLEXIBLE

    // Fixed schedule fields
    private Date fixedStartAt;
    private Date fixedEndAt;

    // Flexible window fields
    private Date windowStartAt;
    private Date windowEndAt;
    private Integer requiredDurationMinutes;
    private String requiredSkillCode;
    private Integer requiredStaffCount;
    private Integer priority; // smaller is more important (convention)
    private Short mustBeContiguous; // 1=true, 0=false

    private String createdBy;
    private Date createdAt;
    private String updatedBy;
    private Date updatedAt;

    private static final long serialVersionUID = 1L;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getStoreCode() {
        return storeCode;
    }

    public void setStoreCode(String storeCode) {
        this.storeCode = storeCode == null ? null : storeCode.trim();
    }

    public Date getWorkDate() {
        return workDate;
    }

    public void setWorkDate(Date workDate) {
        this.workDate = workDate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? null : description.trim();
    }

    public String getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(String scheduleType) {
        this.scheduleType = scheduleType == null ? null : scheduleType.trim();
    }

    public Date getFixedStartAt() {
        return fixedStartAt;
    }

    public void setFixedStartAt(Date fixedStartAt) {
        this.fixedStartAt = fixedStartAt;
    }

    public Date getFixedEndAt() {
        return fixedEndAt;
    }

    public void setFixedEndAt(Date fixedEndAt) {
        this.fixedEndAt = fixedEndAt;
    }

    public Date getWindowStartAt() {
        return windowStartAt;
    }

    public void setWindowStartAt(Date windowStartAt) {
        this.windowStartAt = windowStartAt;
    }

    public Date getWindowEndAt() {
        return windowEndAt;
    }

    public void setWindowEndAt(Date windowEndAt) {
        this.windowEndAt = windowEndAt;
    }

    public Integer getRequiredDurationMinutes() {
        return requiredDurationMinutes;
    }

    public void setRequiredDurationMinutes(Integer requiredDurationMinutes) {
        this.requiredDurationMinutes = requiredDurationMinutes;
    }

    public String getRequiredSkillCode() {
        return requiredSkillCode;
    }

    public void setRequiredSkillCode(String requiredSkillCode) {
        this.requiredSkillCode = requiredSkillCode == null ? null : requiredSkillCode.trim();
    }

    public Integer getRequiredStaffCount() {
        return requiredStaffCount;
    }

    public void setRequiredStaffCount(Integer requiredStaffCount) {
        this.requiredStaffCount = requiredStaffCount;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Short getMustBeContiguous() {
        return mustBeContiguous;
    }

    public void setMustBeContiguous(Short mustBeContiguous) {
        this.mustBeContiguous = mustBeContiguous;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy == null ? null : createdBy.trim();
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy == null ? null : updatedBy.trim();
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
