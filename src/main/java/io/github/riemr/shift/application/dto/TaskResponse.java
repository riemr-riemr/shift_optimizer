package io.github.riemr.shift.application.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Value
@Builder
public class TaskResponse {
    Long taskId;
    String storeCode;
    LocalDate workDate;
    String name;
    String description;
    String scheduleType;
    LocalDateTime fixedStartAt;
    LocalDateTime fixedEndAt;
    LocalDateTime windowStartAt;
    LocalDateTime windowEndAt;
    Integer requiredDurationMinutes;
    String requiredSkillCode;
    Integer requiredStaffCount;
    Integer priority;
    Boolean mustBeContiguous;
}
