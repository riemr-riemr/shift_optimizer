package io.github.riemr.shift.optimization.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Solver の問題識別子。月(YearMonth)と店舗コードで一意。
 * サイクル開始日も保持する。
 */
public final class ProblemKey {
    private final YearMonth month;
    private final String storeCode;
    private final LocalDate cycleStart; // サイクル開始日を追加

    public ProblemKey(YearMonth month, String storeCode) {
        this.month = month;
        this.storeCode = storeCode;
        this.cycleStart = null; // 後方互換性のため
    }
    
    public ProblemKey(YearMonth month, String storeCode, LocalDate cycleStart) {
        this.month = month;
        this.storeCode = storeCode;
        this.cycleStart = cycleStart;
    }

    public YearMonth getMonth() { return month; }
    public String getStoreCode() { return storeCode; }
    public LocalDate getCycleStart() { return cycleStart; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProblemKey that = (ProblemKey) o;
        return Objects.equals(month, that.month) && 
               Objects.equals(storeCode, that.storeCode) && 
               Objects.equals(cycleStart, that.cycleStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(month, storeCode, cycleStart);
    }

    @Override
    public String toString() {
        return month + ":" + storeCode + (cycleStart != null ? ":" + cycleStart : "");
    }
}

