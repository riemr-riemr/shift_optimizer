package io.github.riemr.shift.application.dto;

import java.time.LocalDate;

public record EmployeeRequestDeleteRequest(
    String storeCode,
    String employeeCode,
    LocalDate date
) {}
