package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.sql.Time;

public class EmployeeShiftPattern implements Serializable {
    private String employeeCode;
    private String patternCode;
    private Short priority;
    private Time startTime;
    private Time endTime;
    private Boolean active;

    private static final long serialVersionUID = 1L;

    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public String getPatternCode() { return patternCode; }
    public void setPatternCode(String patternCode) { this.patternCode = patternCode; }
    public Short getPriority() { return priority; }
    public void setPriority(Short priority) { this.priority = priority; }
    public Time getStartTime() { return startTime; }
    public void setStartTime(Time startTime) { this.startTime = startTime; }
    public Time getEndTime() { return endTime; }
    public void setEndTime(Time endTime) { this.endTime = endTime; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
