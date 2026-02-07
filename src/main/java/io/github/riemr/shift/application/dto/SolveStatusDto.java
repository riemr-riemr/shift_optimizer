package io.github.riemr.shift.application.dto;

import java.util.List;

public record SolveStatusDto(
    String status, 
    int progress, 
    long expectedFinishMillis, 
    String phase,
    boolean hasHardConstraintViolations,
    List<String> constraintViolationMessages
) {
    // 既存のコンストラクタとの互換性のため
    public SolveStatusDto(String status, int progress, long expectedFinishMillis) {
        this(status, progress, expectedFinishMillis, null, false, null);
    }
    
    public SolveStatusDto(String status, int progress, long expectedFinishMillis, String phase) {
        this(status, progress, expectedFinishMillis, phase, false, null);
    }
    
    // 制約違反ありのコンストラクタ
    public static SolveStatusDto withConstraintViolations(String status, int progress, long expectedFinishMillis, String phase, List<String> violations) {
        return new SolveStatusDto(status, progress, expectedFinishMillis, phase, true, violations);
    }
}