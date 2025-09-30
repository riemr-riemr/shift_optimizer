package io.github.riemr.shift.application.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class TaskAssignmentResponse {
    Long assignmentId;
    Long taskId;
    String employeeCode;
    LocalDateTime startAt;
    LocalDateTime endAt;
    String source;
    String status;
}
