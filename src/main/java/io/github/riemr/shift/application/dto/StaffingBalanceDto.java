package io.github.riemr.shift.application.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffingBalanceDto {

    private String storeCode;
    
    private LocalDate targetDate;
    
    private LocalTime slotTime;
    
    private Integer requiredStaff;
    
    private Integer assignedStaff;

    public Integer getBalance() {
        if (requiredStaff == null || assignedStaff == null) {
            return null;
        }
        return assignedStaff - requiredStaff;
    }

    public boolean isShortage() {
        Integer balance = getBalance();
        return balance != null && balance < 0;
    }

    public boolean isOverstaffed() {
        Integer balance = getBalance();
        return balance != null && balance > 0;
    }

    public boolean isBalanced() {
        Integer balance = getBalance();
        return balance != null && balance == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StaffingBalanceDto)) return false;
        StaffingBalanceDto that = (StaffingBalanceDto) o;
        return Objects.equals(storeCode, that.storeCode) &&
               Objects.equals(targetDate, that.targetDate) &&
               Objects.equals(slotTime, that.slotTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(storeCode, targetDate, slotTime);
    }

    @Override
    public String toString() {
        return "StaffingBalanceDto{" +
                "storeCode='" + storeCode + '\'' +
                ", targetDate=" + targetDate +
                ", slotTime=" + slotTime +
                ", requiredStaff=" + requiredStaff +
                ", assignedStaff=" + assignedStaff +
                ", balance=" + getBalance() +
                '}';
    }
}