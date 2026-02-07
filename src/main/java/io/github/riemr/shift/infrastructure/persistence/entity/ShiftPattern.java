package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.sql.Time;

public class ShiftPattern implements Serializable {
    private String patternCode;
    private Time startTime;
    private Time endTime;
    private Boolean active;

    private static final long serialVersionUID = 1L;

    public String getPatternCode() { return patternCode; }
    public void setPatternCode(String patternCode) { this.patternCode = patternCode; }
    public Time getStartTime() { return startTime; }
    public void setStartTime(Time startTime) { this.startTime = startTime; }
    public Time getEndTime() { return endTime; }
    public void setEndTime(Time endTime) { this.endTime = endTime; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
