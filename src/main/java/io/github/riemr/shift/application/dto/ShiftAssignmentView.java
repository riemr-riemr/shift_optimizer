package io.github.riemr.shift.application.dto;

public record ShiftAssignmentView(
        String startAt,
        String endAt,
        Integer registerNo,
        String departmentCode,
        String workKind,
        String taskCode,
        String employeeCode,
        String employeeName) {}
