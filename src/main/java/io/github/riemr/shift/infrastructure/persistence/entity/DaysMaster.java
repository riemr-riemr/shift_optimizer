package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

public class DaysMaster implements Serializable {
    private Long daysId;
    private String storeCode;
    private String kind; // WEEKLY or SPECIAL
    private Short dayOfWeek;
    private Date specialDate;
    private String label;
    private Boolean active;

    public Long getDaysId() { return daysId; }
    public void setDaysId(Long daysId) { this.daysId = daysId; }
    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Short getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Short dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public Date getSpecialDate() { return specialDate; }
    public void setSpecialDate(Date specialDate) { this.specialDate = specialDate; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}

