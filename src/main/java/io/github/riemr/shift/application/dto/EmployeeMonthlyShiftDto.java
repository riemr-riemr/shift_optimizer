package io.github.riemr.shift.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 従業員の月間シフト表示用DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeMonthlyShiftDto {
    
    /** 従業員コード */
    private String employeeCode;
    
    /** 従業員名 */
    private String employeeName;
    
    /** 店舗コード */
    private String storeCode;
    
    /** 対象年月 */
    private YearMonth targetMonth;
    
    /** 日別シフト詳細のマップ（日付 -> シフト詳細） */
    private Map<LocalDate, EmployeeShiftDetailDto> dailyShifts;
    
    /** カレンダー表示用の週リスト */
    private List<List<LocalDate>> weeks;
    
    /** 月間統計情報 */
    private MonthlyStats monthlyStats;
    
    /**
     * 月間統計情報
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyStats {
        
        /** 総勤務日数 */
        private int totalWorkDays;
        
        /** 総勤務時間（分） */
        private int totalWorkMinutes;
        
        /** 平均勤務時間/日（分） */
        private int averageWorkMinutesPerDay;
        
        /** 最長勤務時間（分） */
        private int maxWorkMinutes;
        
        /** 最短勤務時間（分） */
        private int minWorkMinutes;
        
        /** 休日数 */
        private int holidayCount;
        
        /** 総勤務時間（時:分形式） */
        public String getTotalWorkTimeFormatted() {
            int hours = totalWorkMinutes / 60;
            int minutes = totalWorkMinutes % 60;
            return String.format("%d:%02d", hours, minutes);
        }
        
        /** 平均勤務時間（時:分形式） */
        public String getAverageWorkTimeFormatted() {
            if (totalWorkDays == 0) return "0:00";
            int hours = averageWorkMinutesPerDay / 60;
            int minutes = averageWorkMinutesPerDay % 60;
            return String.format("%d:%02d", hours, minutes);
        }
        
        /** 最長勤務時間（時:分形式） */
        public String getMaxWorkTimeFormatted() {
            int hours = maxWorkMinutes / 60;
            int minutes = maxWorkMinutes % 60;
            return String.format("%d:%02d", hours, minutes);
        }
        
        /** 最短勤務時間（時:分形式） */
        public String getMinWorkTimeFormatted() {
            int hours = minWorkMinutes / 60;
            int minutes = minWorkMinutes % 60;
            return String.format("%d:%02d", hours, minutes);
        }
    }
}