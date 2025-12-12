package io.github.riemr.shift.infrastructure.persistence.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
public class Employee implements Serializable {
    private String employeeCode;
    private String storeCode;
    private String employeeName;
    private Integer minWorkMinutesDay;
    private Integer maxWorkMinutesDay;
    private Integer minWorkHoursWeek;
    private Integer maxWorkHoursWeek;
    

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

    

    public Integer getMinWorkMinutesDay() {
        return minWorkMinutesDay;
    }

    public void setMinWorkMinutesDay(Integer minWorkMinutesDay) {
        this.minWorkMinutesDay = minWorkMinutesDay;
    }

    public Integer getMaxWorkMinutesDay() {
        return maxWorkMinutesDay;
    }

    public void setMaxWorkMinutesDay(Integer maxWorkMinutesDay) {
        this.maxWorkMinutesDay = maxWorkMinutesDay;
    }

    public Integer getMinWorkHoursWeek() {
        return minWorkHoursWeek;
    }

    public void setMinWorkHoursWeek(Integer minWorkHoursWeek) {
        this.minWorkHoursWeek = minWorkHoursWeek;
    }

    public Integer getMaxWorkHoursWeek() {
        return maxWorkHoursWeek;
    }

    public void setMaxWorkHoursWeek(Integer maxWorkHoursWeek) {
        this.maxWorkHoursWeek = maxWorkHoursWeek;
    }


    
}
