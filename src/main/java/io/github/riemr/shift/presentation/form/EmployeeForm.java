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


    @NotNull(message = "1日上限時間は必須です")
    @Min(value = 1, message = "1日上限時間は1分以上である必要があります")
    @Max(value = 1440, message = "1日上限時間は1440分以下である必要があります")
    private Integer maxWorkMinutesDay;

    @NotNull(message = "1日下限時間は必須です")
    @Min(value = 0, message = "1日下限時間は0分以上である必要があります")
    @Max(value = 1440, message = "1日下限時間は1440分以下である必要があります")
    private Integer minWorkMinutesDay;

    

    @NotNull(message = "1週下限時間は必須です")
    @Min(value = 0, message = "1週下限時間は0時間以上である必要があります")
    @Max(value = 100, message = "1週下限時間は100時間以下である必要があります")
    private Integer minWorkHoursWeek;

    @NotNull(message = "1週上限時間は必須です")
    @Min(value = 0, message = "1週上限時間は0時間以上である必要があります")
    @Max(value = 100, message = "1週上限時間は100時間以下である必要があります")
    private Integer maxWorkHoursWeek;
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
        Employee e = new Employee();
        e.setEmployeeCode(employeeCode);
        e.setStoreCode(storeCode);
        e.setEmployeeName(employeeName);
        
        e.setMinWorkMinutesDay(minWorkMinutesDay);
        e.setMaxWorkMinutesDay(maxWorkMinutesDay);
        
        e.setMinWorkHoursWeek(minWorkHoursWeek);
        e.setMaxWorkHoursWeek(maxWorkHoursWeek);
        return e;
    }

    public static EmployeeForm from(Employee e) {
        EmployeeForm f = new EmployeeForm();
        f.employeeCode = e.getEmployeeCode();
        f.storeCode = e.getStoreCode();
        f.employeeName = e.getEmployeeName();
        
        f.minWorkMinutesDay = e.getMinWorkMinutesDay();
        f.maxWorkMinutesDay = e.getMaxWorkMinutesDay();
        
        f.minWorkHoursWeek = e.getMinWorkHoursWeek();
        f.maxWorkHoursWeek = e.getMaxWorkHoursWeek();

        
        return f;
    }

    // 月別設定（UI入力用）
    private Integer selectedYear; // 年選択用
    
    @lombok.Data
    public static class MonthlyHoursRow {
        private String month; // yyyy-MM
        private Integer minHours;
        private Integer maxHours;
    }
    private java.util.List<MonthlyHoursRow> monthlyHours = new java.util.ArrayList<>();
    
    // 月別時間設定（テーブル形式用）
    @lombok.Data
    public static class MonthlyHoursTableData {
        private Integer[] minHours = new Integer[12]; // 1月～12月の下限
        private Integer[] maxHours = new Integer[12]; // 1月～12月の上限
    }
    private MonthlyHoursTableData monthlyHoursTable = new MonthlyHoursTableData();
}
