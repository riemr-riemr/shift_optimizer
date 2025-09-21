package io.github.riemr.shift.presentation.form;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

@Data
public class EmployeeForm {
    @NotBlank
    private String employeeCode;

    @NotBlank
    private String storeCode;

    @NotBlank
    private String employeeName;

    @NotNull
    @Min(0)
    @Max(4)
    private Short shortFollow;

    @NotNull @Min(1) @Max(1440)
    private Integer maxWorkMinutesDay;

    @NotNull @Min(1) @Max(31)
    private Integer maxWorkDaysMonth;
    // 曜日別設定（1=Mon .. 7=Sun）
    @lombok.Data
    public static class WeeklyPrefRow {
        private Short dayOfWeek;
        private String workStyle; // OFF / OPTIONAL / MANDATORY
        private String baseStartTime; // HH:mm or null
        private String baseEndTime;   // HH:mm or null
    }
    private java.util.List<WeeklyPrefRow> weeklyPreferences = new java.util.ArrayList<>();

    

    /* -------- DTO ⇔ Entity 変換 -------- */
    public Employee toEntity() {
        return new Employee(employeeCode, storeCode, employeeName,
                shortFollow, maxWorkMinutesDay, maxWorkDaysMonth);
    }

    public static EmployeeForm from(Employee e) {
        EmployeeForm f = new EmployeeForm();
        f.employeeCode = e.getEmployeeCode();
        f.storeCode = e.getStoreCode();
        f.employeeName = e.getEmployeeName();
        f.shortFollow = e.getShortFollow();
        f.maxWorkMinutesDay = e.getMaxWorkMinutesDay();
        f.maxWorkDaysMonth = e.getMaxWorkDaysMonth();

        
        return f;
    }
}
