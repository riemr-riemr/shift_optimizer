package io.github.riemr.shift.application.dto;

import java.time.LocalDate;
import java.util.List;

public record ShiftAssignmentSaveRequest(
    LocalDate date,
    List<ShiftAssignmentChange> changes
) {
    public record ShiftAssignmentChange(
        String employeeCode,
        String time,
        String original,
        String current
    ) {}
}