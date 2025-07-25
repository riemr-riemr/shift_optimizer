package io.github.riemr.shift.presentation.form;

import io.github.riemr.shift.domain.Employee;
import jakarta.validation.constraints.*;
import lombok.Data;

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
    @Max(1)
    private Short shortFollow;

    @NotNull @Min(1) @Max(720)
    private Integer maxWorkMinutesDay;

    @NotNull @Min(1) @Max(31)
    private Integer maxWorkDaysMonth;

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