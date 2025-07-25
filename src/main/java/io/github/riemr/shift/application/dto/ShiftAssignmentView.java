package io.github.riemr.shift.application.dto;

public record ShiftAssignmentView(
        String startAt,
        String endAt,
        int registerNo,
        String employeeName) {}
