package io.github.riemr.shift.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TaskCreateRequest {
    @NotBlank
    private String storeCode;

    @NotNull
    private LocalDate workDate;

    @NotBlank
    private String name;

    private String description;

    @NotBlank
    private String scheduleType; // FIXED | FLEXIBLE

    // Fixed
    private LocalDateTime fixedStartAt;
    private LocalDateTime fixedEndAt;
    @Min(1)
    private Integer requiredStaffCount; // for FIXED

    // Flexible
    private LocalDateTime windowStartAt;
    private LocalDateTime windowEndAt;
    @Min(1)
    private Integer requiredDurationMinutes;
    private Boolean mustBeContiguous = Boolean.TRUE;

    // Common
    private String requiredSkillCode;
    private Integer priority;
}
