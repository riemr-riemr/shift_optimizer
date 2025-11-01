package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;

public class EmployeeMonthlyOffdaysSetting implements Serializable {
    private String employeeCode;
    private Date monthStart; // その月の1日
    private Integer minOffDays;
    private Integer maxOffDays;

    private static final long serialVersionUID = 1L;

    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public Date getMonthStart() { return monthStart; }
    public void setMonthStart(Date monthStart) { this.monthStart = monthStart; }
    public Integer getMinOffDays() { return minOffDays; }
    public void setMinOffDays(Integer minOffDays) { this.minOffDays = minOffDays; }
    public Integer getMaxOffDays() { return maxOffDays; }
    public void setMaxOffDays(Integer maxOffDays) { this.maxOffDays = maxOffDays; }
}

