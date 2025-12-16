package io.github.riemr.shift.optimization.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class RegisterDemandSlot {
    private String storeCode;
    private LocalDate demandDate;
    private LocalTime slotTime;
    private Integer requiredUnits;
}

