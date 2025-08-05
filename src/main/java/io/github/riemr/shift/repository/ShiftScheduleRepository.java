package io.github.riemr.shift.repository;

import java.time.LocalDate;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;

public interface ShiftScheduleRepository {
    /**
     * 指定月のシフト計算用データをすべて取得し、ドメインモデルへ変換する。
     * @param month 例: 2025‑07‑01 (日付は 1 日で固定)
     */
    ShiftSchedule fetchShiftSchedule(LocalDate month);
}