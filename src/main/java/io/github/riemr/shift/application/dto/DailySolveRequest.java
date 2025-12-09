package io.github.riemr.shift.application.dto;

public record DailySolveRequest(
        String date,
        String storeCode,
        String departmentCode
) {}

