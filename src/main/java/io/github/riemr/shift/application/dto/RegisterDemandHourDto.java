package io.github.riemr.shift.application.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import lombok.NoArgsConstructor;

/**
 * Hourly–granularity demand for registers.
 * <p>
 * UI では 1 時間単位で入力しますが、DB では区間需要（`register_demand_interval`）で保持します。
 */
@NoArgsConstructor
public class RegisterDemandHourDto {

    private String storeCode;
    private LocalDate demandDate;
    /**
     * 00:00, 01:00, ... 23:00 のみを許容します。
     */
    private LocalTime hour;
    /**
     * その 1 時間帯に必要なレジ台数
     */
    private Integer requiredUnits;

    public RegisterDemandHourDto(String storeCode, LocalDate demandDate, LocalTime hour, Integer requiredUnits) {
        this.storeCode = storeCode;
        this.demandDate = demandDate;
        this.hour = hour.truncatedTo(ChronoUnit.HOURS);
        this.requiredUnits = requiredUnits;
    }

    /* ==================== getter / setter ==================== */

    public String getStoreCode() {
        return storeCode;
    }

    public void setStoreCode(String storeCode) {
        this.storeCode = storeCode;
    }

    public LocalDate getDemandDate() {
        return demandDate;
    }

    public void setDemandDate(LocalDate demandDate) {
        this.demandDate = demandDate;
    }

    public LocalTime getHour() {
        return hour;
    }

    public void setHour(LocalTime hour) {
        this.hour = hour.truncatedTo(ChronoUnit.HOURS);
    }

    public Integer getRequiredUnits() {
        return requiredUnits;
    }

    public void setRequiredUnits(Integer requiredUnits) {
        this.requiredUnits = requiredUnits;
    }

    /* ==================== util ==================== */

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegisterDemandHourDto)) return false;
        RegisterDemandHourDto that = (RegisterDemandHourDto) o;
        return Objects.equals(storeCode, that.storeCode) &&
               Objects.equals(demandDate, that.demandDate) &&
               Objects.equals(hour, that.hour);
    }

    @Override
    public int hashCode() {
        return Objects.hash(storeCode, demandDate, hour);
    }

    @Override
    public String toString() {
        return "RegisterDemandHour{" +
                "storeCode='" + storeCode + '\'' +
                ", date=" + demandDate +
                ", hour=" + hour +
                ", requiredUnits=" + requiredUnits +
                '}';
    }
}
