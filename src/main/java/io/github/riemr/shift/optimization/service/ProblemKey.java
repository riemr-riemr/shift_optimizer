package io.github.riemr.shift.optimization.service;

import java.time.YearMonth;
import java.util.Objects;

/**
 * Solver の問題識別子。月(YearMonth)と店舗コードで一意。
 */
public final class ProblemKey {
    private final YearMonth month;
    private final String storeCode;

    public ProblemKey(YearMonth month, String storeCode) {
        this.month = month;
        this.storeCode = storeCode;
    }

    public YearMonth getMonth() { return month; }
    public String getStoreCode() { return storeCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProblemKey that = (ProblemKey) o;
        return Objects.equals(month, that.month) && Objects.equals(storeCode, that.storeCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(month, storeCode);
    }

    @Override
    public String toString() {
        return month + ":" + storeCode;
    }
}

