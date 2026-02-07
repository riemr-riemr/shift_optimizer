package io.github.riemr.shift.optimization.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class WorkDemandSlot {
    private String storeCode;
    private String departmentCode;
    private LocalDate demandDate;
    private LocalTime slotTime;
    private String taskCode;
    private Integer requiredUnits;
}

