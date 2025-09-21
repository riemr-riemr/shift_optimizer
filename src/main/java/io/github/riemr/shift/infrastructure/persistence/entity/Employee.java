package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
public class Employee implements Serializable {
    private String employeeCode;
    private String storeCode;
    private String employeeName;
    private Short shortFollow;
    private Integer maxWorkMinutesDay;
    private Integer maxWorkDaysMonth;
    

    private static final long serialVersionUID = 1L;

    public String getEmployeeCode() {
        return employeeCode;
    }

    public void setEmployeeCode(String employeeCode) {
        this.employeeCode = employeeCode == null ? null : employeeCode.trim();
    }

    public String getStoreCode() {
        return storeCode;
    }

    public void setStoreCode(String storeCode) {
        this.storeCode = storeCode == null ? null : storeCode.trim();
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName == null ? null : employeeName.trim();
    }

    public Short getShortFollow() {
        return shortFollow;
    }

    public void setShortFollow(Short shortFollow) {
        this.shortFollow = shortFollow;
    }

    public Integer getMaxWorkMinutesDay() {
        return maxWorkMinutesDay;
    }

    public void setMaxWorkMinutesDay(Integer maxWorkMinutesDay) {
        this.maxWorkMinutesDay = maxWorkMinutesDay;
    }

    public Integer getMaxWorkDaysMonth() {
        return maxWorkDaysMonth;
    }

    public void setMaxWorkDaysMonth(Integer maxWorkDaysMonth) {
        this.maxWorkDaysMonth = maxWorkDaysMonth;
    }

    
}
