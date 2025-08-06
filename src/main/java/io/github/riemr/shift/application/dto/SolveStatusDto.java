package io.github.riemr.shift.application.dto;

public record SolveStatusDto(String status, int progress, long expectedFinishMillis, String phase) {
    // 既存のコンストラクタとの互換性のため
    public SolveStatusDto(String status, int progress, long expectedFinishMillis) {
        this(status, progress, expectedFinishMillis, null);
    }
}