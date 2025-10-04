package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

public class DepartmentTaskAssignment implements Serializable {
    private Long assignmentId;
    private String storeCode;
    private String departmentCode;
    private String taskCode; // nullable
    private String employeeCode;
    private Date startAt;
    private Date endAt;
    private String createdBy;

    public Long getAssignmentId() { return assignmentId; }
    public void setAssignmentId(Long assignmentId) { this.assignmentId = assignmentId; }
    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public Date getStartAt() { return startAt; }
    public void setStartAt(Date startAt) { this.startAt = startAt; }
    public Date getEndAt() { return endAt; }
    public void setEndAt(Date endAt) { this.endAt = endAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}

