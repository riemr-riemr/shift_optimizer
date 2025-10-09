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
public class QuarterSlot {
    private String storeCode;
    private String departmentCode; // optional
    private LocalDate date;
    private LocalTime start; // aligned to 15-min
    private Integer demand;
    private String taskCode; // optional
}

