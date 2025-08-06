package io.github.riemr.shift.application.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import lombok.NoArgsConstructor;

/**
 * Hourly–granularity demand for registers.
 * <p>
 * UI では 1 時間単位で入力しますが、DB では 15 分単位(register_demand_quarter)
 * で保持しているため、本クラスは 1 時間 → 15 分スロット × 4 への変換を担います。
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

    /* ==================== Convert ==================== */

    /**
     * 1 時間需要を 15 分需要へ展開します。
     */
    public List<RegisterDemandQuarter> toQuarterEntities() {
        List<RegisterDemandQuarter> list = new ArrayList<>(4);
        for (int q = 0; q < 4; q++) {
            LocalTime slot = hour.plusMinutes(15L * q);
            RegisterDemandQuarter ent = new RegisterDemandQuarter();
            ent.setStoreCode(storeCode);
            ent.setDemandDate(java.sql.Date.valueOf(demandDate));
            ent.setSlotTime(slot);
            ent.setRequiredUnits(requiredUnits);
            list.add(ent);
        }
        return list;
    }

    /**
     * 15 分需要 (同一 store/date/hour) をまとめて 1 時間需要に変換します。
     * <p>
     * 単純に最初の行から代表値を取り、requiredUnits は平均ではなく先頭行の値を採用します
     * （UI では 4 本とも同一値で保存している前提）。
     */
    public static RegisterDemandHourDto fromQuarterEntities(List<RegisterDemandQuarter> quarters) {
        if (quarters == null || quarters.isEmpty()) throw new IllegalArgumentException("quarters is empty");
        RegisterDemandQuarter first = quarters.get(0);
        LocalTime hour = first.getSlotTime().truncatedTo(ChronoUnit.HOURS);
        return new RegisterDemandHourDto(
                first.getStoreCode(),
                first.getDemandDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate(),
                hour,
                first.getRequiredUnits());
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