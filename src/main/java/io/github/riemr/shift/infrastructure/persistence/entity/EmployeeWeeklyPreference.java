package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.sql.Time;
import java.util.Date;

public class EmployeeWeeklyPreference implements Serializable {
    private String employeeCode;
    private Short dayOfWeek; // 1=Mon ... 7=Sun
    private String workStyle; // OFF / OPTIONAL / MANDATORY
    private Time baseStartTime; // nullable unless workStyle != OFF
    private Time baseEndTime;   // nullable unless workStyle != OFF
    private String storeCode;   // optional
    private Date createdAt;
    private Date updatedAt;

    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public Short getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Short dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public String getWorkStyle() { return workStyle; }
    public void setWorkStyle(String workStyle) { this.workStyle = workStyle; }
    public Time getBaseStartTime() { return baseStartTime; }
    public void setBaseStartTime(Time baseStartTime) { this.baseStartTime = baseStartTime; }
    public Time getBaseEndTime() { return baseEndTime; }
    public void setBaseEndTime(Time baseEndTime) { this.baseEndTime = baseEndTime; }
    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}

