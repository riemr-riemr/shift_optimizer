package io.github.riemr.shift.application.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月別カレンダー表示用 DTO
 * <p>
 * <ul>
 *   <li>{@code startAt}/{@code endAt} は 15 分単位の実シフト時刻</li>
 *   <li>{@code date()} アクセッサで LocalDate が取れるため
 *       グルーピング時に {@code view.date().getDayOfMonth()} が呼べる</li>
 * </ul>
 */
public record ShiftAssignmentMonthlyView(
        LocalDateTime startAt,
        LocalDateTime endAt,
        Integer registerNo,
        String employeeCode, // Add employeeCode
        String employeeName,
        boolean manualEdit) {

    /** 開始日時から日付を取得（カレンダー集計用） */
    public LocalDate date() {
        return startAt.toLocalDate();
    }
}
