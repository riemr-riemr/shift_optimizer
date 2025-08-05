package io.github.riemr.shift.presentation.form;

import io.github.riemr.shift.domain.Employee;
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

    private String baseStartTime;
    private String baseEndTime;

    /* -------- DTO ⇔ Entity 変換 -------- */
    public Employee toEntity() {
        Date startTime = null;
        Date endTime = null;
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        try {
            if (baseStartTime != null && !baseStartTime.isEmpty()) {
                startTime = sdf.parse(baseStartTime);
            }
            if (baseEndTime != null && !baseEndTime.isEmpty()) {
                endTime = sdf.parse(baseEndTime);
            }
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid time format, please use HH:mm", e);
        }

        return new Employee(employeeCode, storeCode, employeeName,
                shortFollow, maxWorkMinutesDay, maxWorkDaysMonth, startTime, endTime);
    }

    public static EmployeeForm from(Employee e) {
        EmployeeForm f = new EmployeeForm();
        f.employeeCode = e.getEmployeeCode();
        f.storeCode = e.getStoreCode();
        f.employeeName = e.getEmployeeName();
        f.shortFollow = e.getShortFollow();
        f.maxWorkMinutesDay = e.getMaxWorkMinutesDay();
        f.maxWorkDaysMonth = e.getMaxWorkDaysMonth();

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        if (e.getBaseStartTime() != null) {
            f.baseStartTime = sdf.format(e.getBaseStartTime());
        }
        if (e.getBaseEndTime() != null) {
            f.baseEndTime = sdf.format(e.getBaseEndTime());
        }
        return f;
    }
}