package io.github.riemr.shift.application.dto;

public record SolveStatusDto(String status, int progress, long expectedFinishMillis) {}