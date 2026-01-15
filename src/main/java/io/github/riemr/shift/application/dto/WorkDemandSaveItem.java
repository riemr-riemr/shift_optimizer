package io.github.riemr.shift.application.dto;

import lombok.Data;

@Data
public class WorkDemandSaveItem {
    private String taskCode;
    private String departmentCode;
    private String fromTime; // HH:mm
    private String toTime;   // HH:mm
    private Integer demand;
    private Integer lane;
}
