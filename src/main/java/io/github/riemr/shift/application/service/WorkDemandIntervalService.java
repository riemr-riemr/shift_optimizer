package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.dto.DemandIntervalDto;
import io.github.riemr.shift.application.dto.WorkDemandSaveItem;
import io.github.riemr.shift.infrastructure.mapper.WorkDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
public class WorkDemandIntervalService {
    private final WorkDemandIntervalMapper workDemandIntervalMapper;
    private final TaskMasterService taskMasterService;

    public WorkDemandIntervalService(WorkDemandIntervalMapper workDemandIntervalMapper,
                                     TaskMasterService taskMasterService) {
        this.workDemandIntervalMapper = workDemandIntervalMapper;
        this.taskMasterService = taskMasterService;
    }

    public List<DemandIntervalDto> list(String storeCode, LocalDate date, String departmentCode) {
        if (storeCode == null || storeCode.isBlank() || departmentCode == null || departmentCode.isBlank()) {
            return List.of();
        }
        return workDemandIntervalMapper.selectByDate(storeCode, departmentCode, date);
    }

    @Transactional
    public void replaceForDate(String storeCode,
                               LocalDate date,
                               String departmentCode,
                               List<WorkDemandSaveItem> intervals) {
        if (storeCode == null || storeCode.isBlank() || departmentCode == null || departmentCode.isBlank()) {
            throw new IllegalArgumentException("storeCode and departmentCode are required");
        }
        LocalDate from = date;
        LocalDate to = date.plusDays(1);
        workDemandIntervalMapper.deleteByStoreDeptAndRange(storeCode, departmentCode, from, to);
        if (intervals == null || intervals.isEmpty()) {
            return;
        }
        for (WorkDemandSaveItem item : intervals) {
            if (item.getTaskCode() == null || item.getTaskCode().isBlank()) {
                throw new IllegalArgumentException("taskCode is required");
            }
            LocalTime start = parseTime(item.getFromTime());
            LocalTime end = parseTime(item.getToTime());
            if (start == null || end == null || !end.isAfter(start)) {
                throw new IllegalArgumentException("fromTime < toTime required");
            }
            Integer demand = item.getDemand() != null ? item.getDemand() : 1;
            if (demand < 1) {
                throw new IllegalArgumentException("demand >= 1 required");
            }
            String dept = item.getDepartmentCode() != null ? item.getDepartmentCode() : departmentCode;
            TaskMaster master = taskMasterService.get(item.getTaskCode(), dept);
            if (master == null) {
                throw new IllegalArgumentException("task master not found: " + item.getTaskCode());
            }
            workDemandIntervalMapper.insert(DemandIntervalDto.builder()
                    .storeCode(storeCode)
                    .departmentCode(dept)
                    .targetDate(date)
                    .from(start)
                    .to(end)
                    .demand(demand)
                    .taskCode(item.getTaskCode())
                    .lane(item.getLane())
                    .build());
        }
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalTime.parse(value);
    }
}
