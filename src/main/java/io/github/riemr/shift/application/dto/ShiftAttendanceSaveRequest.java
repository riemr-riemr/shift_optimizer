package io.github.riemr.shift.application.dto;

import java.time.LocalDate;

public record ShiftAttendanceSaveRequest(
    String storeCode,
    String employeeCode,
    LocalDate date,
    String startTime,
    String endTime,
    String offKind
) {}
