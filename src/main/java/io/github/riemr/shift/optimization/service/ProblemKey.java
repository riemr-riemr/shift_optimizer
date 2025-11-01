package io.github.riemr.shift.optimization.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Solver の問題識別子。月(YearMonth)と店舗コード、部門コードで一意。
 * サイクル開始日も保持する。
 */
public final class ProblemKey {
    private final YearMonth month;
    private final String storeCode;
    private final String departmentCode;
    private final LocalDate cycleStart; // サイクル開始日を追加
    private final String stage; // ATTENDANCE or ASSIGNMENT

    public ProblemKey(YearMonth month, String storeCode) {
        this.month = month;
        this.storeCode = storeCode;
        this.departmentCode = null;
        this.cycleStart = null; // 後方互換性のため
        this.stage = null;
    }
    
    public ProblemKey(YearMonth month, String storeCode, LocalDate cycleStart) {
        this.month = month;
        this.storeCode = storeCode;
        this.departmentCode = null;
        this.cycleStart = cycleStart;
        this.stage = null;
    }

    public ProblemKey(YearMonth month, String storeCode, String departmentCode, LocalDate cycleStart) {
        this.month = month;
        this.storeCode = storeCode;
        this.departmentCode = departmentCode;
        this.cycleStart = cycleStart;
        this.stage = null;
    }

    public ProblemKey(YearMonth month, String storeCode, String departmentCode, LocalDate cycleStart, String stage) {
        this.month = month;
        this.storeCode = storeCode;
        this.departmentCode = departmentCode;
        this.cycleStart = cycleStart;
        this.stage = stage;
    }

    public YearMonth getMonth() { return month; }
    public String getStoreCode() { return storeCode; }
    public String getDepartmentCode() { return departmentCode; }
    public LocalDate getCycleStart() { return cycleStart; }
    public String getStage() { return stage; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProblemKey that = (ProblemKey) o;
        return Objects.equals(month, that.month) && 
               Objects.equals(storeCode, that.storeCode) &&
               Objects.equals(departmentCode, that.departmentCode) &&
               Objects.equals(cycleStart, that.cycleStart) &&
               Objects.equals(stage, that.stage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(month, storeCode, departmentCode, cycleStart, stage);
    }

    @Override
    public String toString() {
        return month + ":" + storeCode +
               (departmentCode != null ? ":" + departmentCode : "") +
               (cycleStart != null ? ":" + cycleStart : "") +
               (stage != null ? ":" + stage : "");
    }
}
