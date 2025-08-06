package io.github.riemr.shift.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 従業員の個人シフト詳細情報を表すDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeShiftDetailDto {
    
    /** 日付 */
    private LocalDate workDate;
    
    /** 開始時刻 */
    private LocalTime startTime;
    
    /** 終了時刻 */
    private LocalTime endTime;
    
    /** 勤務時間（分） */
    private int workMinutes;
    
    /** レジスター番号 */
    private Integer registerNo;
    
    /** 店舗コード */
    private String storeCode;
    
    /** 休日フラグ */
    private boolean isHoliday;
    
    /** 勤務時間（時:分形式） */
    public String getWorkTimeFormatted() {
        int hours = workMinutes / 60;
        int minutes = workMinutes % 60;
        return String.format("%d:%02d", hours, minutes);
    }
    
    /** 勤務時間帯（開始-終了形式） */
    public String getWorkPeriod() {
        if (startTime == null || endTime == null) {
            return "休日";
        }
        return String.format("%s - %s", 
            startTime.toString(), 
            endTime.toString());
    }
}