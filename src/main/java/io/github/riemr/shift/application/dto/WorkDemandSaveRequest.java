package io.github.riemr.shift.application.dto;

import lombok.Data;

import java.util.List;

@Data
public class WorkDemandSaveRequest {
    private String storeCode;
    private String date; // yyyy-MM-dd
    private String departmentCode;
    private List<WorkDemandSaveItem> intervals;
}
