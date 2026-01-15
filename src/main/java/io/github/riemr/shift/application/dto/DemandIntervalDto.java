package io.github.riemr.shift.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemandIntervalDto {
    private String storeCode;
    private String departmentCode; // optional, used for work demand
    private LocalDate targetDate;
    private LocalTime from;
    private LocalTime to;
    private Integer demand;
    private String taskCode; // optional
    private Integer lane; // optional
    private Integer registerNo; // optional, for register demand
    private String groupId; // optional, to keep continuity grouping if needed
}
