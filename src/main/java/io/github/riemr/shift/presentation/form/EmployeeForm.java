package io.github.riemr.shift.presentation.form;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

@Data
public class EmployeeForm {
    @NotBlank(message = "従業員コードは必須です")
    private String employeeCode;

    @NotBlank(message = "店舗コードは必須です")
    private String storeCode;

    @NotBlank(message = "氏名は必須です")
    private String employeeName;

    @NotNull(message = "ショートフォロー区分は必須です")
    @Min(value = 0, message = "ショートフォロー区分は0以上である必要があります")
    @Max(value = 4, message = "ショートフォロー区分は4以下である必要があります")
    private Short shortFollow;

    @NotNull(message = "1日上限時間は必須です")
    @Min(value = 1, message = "1日上限時間は1分以上である必要があります")
    @Max(value = 1440, message = "1日上限時間は1440分以下である必要があります")
    private Integer maxWorkMinutesDay;

    @NotNull(message = "1ヶ月上限日数は必須です")
    @Min(value = 1, message = "1ヶ月上限日数は1日以上である必要があります")
    @Max(value = 31, message = "1ヶ月上限日数は31日以下である必要があります")
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
