package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.repository.MonthlyTaskPlanRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.MonthlyTaskPlan;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.*;

@RestController
@RequestMapping("/tasks/api/monthly")
public class MonthlyTaskPlanController {
    private final MonthlyTaskPlanRepository repository;

    public MonthlyTaskPlanController(MonthlyTaskPlanRepository repository) {
        this.repository = repository;
    }

    public static class DomRequest {
        public String storeCode;
        public String departmentCode;
        public String taskCode;
        public String scheduleType; // FIXED or FLEXIBLE
        @DateTimeFormat(pattern = "HH:mm") public Date fixedStartTime;
        @DateTimeFormat(pattern = "HH:mm") public Date fixedEndTime;
        @DateTimeFormat(pattern = "HH:mm") public Date windowStartTime;
        @DateTimeFormat(pattern = "HH:mm") public Date windowEndTime;
        public Integer requiredDurationMinutes;
        public Integer requiredStaffCount;
        public Integer lane;
        public Short mustBeContiguous;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) public Date effectiveFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) public Date effectiveTo;
        public Integer priority;
        public String note;
        public Boolean active = true;

        public List<Short> daysOfMonth; // 1..31
    }

    public static class WomRequest {
        public String storeCode;
        public String departmentCode;
        public String taskCode;
        public String scheduleType;
        @DateTimeFormat(pattern = "HH:mm") public Date fixedStartTime;
        @DateTimeFormat(pattern = "HH:mm") public Date fixedEndTime;
        @DateTimeFormat(pattern = "HH:mm") public Date windowStartTime;
        @DateTimeFormat(pattern = "HH:mm") public Date windowEndTime;
        public Integer requiredDurationMinutes;
        public Integer requiredStaffCount;
        public Integer lane;
        public Short mustBeContiguous;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) public Date effectiveFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) public Date effectiveTo;
        public Integer priority;
        public String note;
        public Boolean active = true;

        public List<Short> weeksOfMonth; // 1..5
        public List<Short> daysOfWeek;   // 1..7 (ISO, Mon=1)
    }

    @PostMapping(path = "/dom", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createDom(@RequestBody DomRequest req) {
        validateCommon(req.storeCode, req.taskCode, req.scheduleType);
        if (req.daysOfMonth == null || req.daysOfMonth.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error","daysOfMonth is required"));
        }
        MonthlyTaskPlan p = toPlan(req.storeCode, req.departmentCode, req.taskCode, req.scheduleType,
                req.fixedStartTime, req.fixedEndTime, req.windowStartTime, req.windowEndTime,
                req.requiredDurationMinutes, req.requiredStaffCount, req.lane, req.mustBeContiguous,
                req.effectiveFrom, req.effectiveTo, req.priority, req.note, req.active);
        repository.save(p);
        repository.replaceDomDays(p.getPlanId(), req.daysOfMonth);
        return ResponseEntity.ok(Map.of("planId", p.getPlanId(), "daysCount", req.daysOfMonth.size()));
    }

    @PostMapping(path = "/wom", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createWom(@RequestBody WomRequest req) {
        validateCommon(req.storeCode, req.taskCode, req.scheduleType);
        if (req.weeksOfMonth == null || req.weeksOfMonth.isEmpty() || req.daysOfWeek == null || req.daysOfWeek.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error","weeksOfMonth and daysOfWeek are required"));
        }
        MonthlyTaskPlan p = toPlan(req.storeCode, req.departmentCode, req.taskCode, req.scheduleType,
                req.fixedStartTime, req.fixedEndTime, req.windowStartTime, req.windowEndTime,
                req.requiredDurationMinutes, req.requiredStaffCount, req.lane, req.mustBeContiguous,
                req.effectiveFrom, req.effectiveTo, req.priority, req.note, req.active);
        repository.save(p);
        repository.replaceWomPairs(p.getPlanId(), req.weeksOfMonth, req.daysOfWeek);
        int pairs = req.weeksOfMonth.size() * req.daysOfWeek.size();
        return ResponseEntity.ok(Map.of("planId", p.getPlanId(), "pairs", pairs));
    }

    @GetMapping(path = "/effective", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MonthlyTaskPlan> listEffective(@RequestParam("store") String storeCode,
                                               @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) Date date) {
        return repository.listEffectiveByStoreAndDate(storeCode, date);
    }

    @GetMapping(path = "/calendar", produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.Map<String, java.util.List<java.util.Map<String,Object>>> monthCalendar(
            @RequestParam("store") String storeCode,
            @RequestParam(name = "dept", required = false) String departmentCode,
            @RequestParam("month") String yearMonthStr) {
        java.time.YearMonth ym = java.time.YearMonth.parse(yearMonthStr);
        java.time.LocalDate from = ym.atDay(1);
        java.time.LocalDate to = ym.atEndOfMonth();
        java.util.Map<String, java.util.List<java.util.Map<String,Object>>> result = new java.util.LinkedHashMap<>();
        for (java.time.LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            java.util.Date dd = java.util.Date.from(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
            java.util.List<MonthlyTaskPlan> list = repository.listEffectiveByStoreAndDate(storeCode, dd);
            // Optional department filter
            if (departmentCode != null && !departmentCode.isBlank()) {
                list = list.stream().filter(p -> departmentCode.equals(p.getDepartmentCode())).toList();
            }
            java.util.List<java.util.Map<String,Object>> simple = new java.util.ArrayList<>();
            for (MonthlyTaskPlan p : list) {
                java.util.Map<String,Object> m = new java.util.HashMap<>();
                m.put("planId", p.getPlanId());
                m.put("departmentCode", p.getDepartmentCode());
                m.put("taskCode", p.getTaskCode());
                m.put("scheduleType", p.getScheduleType());
                if (p.getFixedStartTime() != null) m.put("fixedStartTime", new java.text.SimpleDateFormat("HH:mm").format(p.getFixedStartTime()));
                if (p.getFixedEndTime() != null) m.put("fixedEndTime", new java.text.SimpleDateFormat("HH:mm").format(p.getFixedEndTime()));
                if (p.getWindowStartTime() != null) m.put("windowStartTime", new java.text.SimpleDateFormat("HH:mm").format(p.getWindowStartTime()));
                if (p.getWindowEndTime() != null) m.put("windowEndTime", new java.text.SimpleDateFormat("HH:mm").format(p.getWindowEndTime()));
                if (p.getRequiredDurationMinutes() != null) m.put("requiredDurationMinutes", p.getRequiredDurationMinutes());
                if (p.getRequiredStaffCount() != null) m.put("requiredStaffCount", p.getRequiredStaffCount());
                if (p.getLane() != null) m.put("lane", p.getLane());
                if (p.getPriority() != null) m.put("priority", p.getPriority());
                if (p.getActive() != null) m.put("active", p.getActive());
                if (p.getMustBeContiguous() != null) m.put("mustBeContiguous", p.getMustBeContiguous() != 0);
                if (p.getNote() != null) m.put("note", p.getNote());
                if (p.getEffectiveFrom() != null) m.put("effectiveFrom", new java.text.SimpleDateFormat("yyyy-MM-dd").format(p.getEffectiveFrom()));
                if (p.getEffectiveTo() != null) m.put("effectiveTo", new java.text.SimpleDateFormat("yyyy-MM-dd").format(p.getEffectiveTo()));
                simple.add(m);
            }
            result.put(d.toString(), simple);
        }
        return result;
    }

    @DeleteMapping(path = "/delete/{id}")
    public ResponseEntity<?> deletePlan(@PathVariable("id") Long planId) {
        repository.delete(planId);
        return ResponseEntity.ok().build();
    }

    @PutMapping(path = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updatePlan(@RequestBody MonthlyTaskPlan req) {
        if (req.getPlanId() == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "planId is required"));
        }
        MonthlyTaskPlan existing = repository.find(req.getPlanId());
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        // Only overwrite fields that are provided (non-null) in request
        if (req.getScheduleType() != null) existing.setScheduleType(req.getScheduleType());
        if (req.getFixedStartTime() != null ||
            ("FIXED".equalsIgnoreCase(existing.getScheduleType()) && req.getFixedStartTime() == null)) {
            existing.setFixedStartTime(req.getFixedStartTime());
        }
        if (req.getFixedEndTime() != null ||
            ("FIXED".equalsIgnoreCase(existing.getScheduleType()) && req.getFixedEndTime() == null)) {
            existing.setFixedEndTime(req.getFixedEndTime());
        }
        if (req.getWindowStartTime() != null ||
            ("FLEXIBLE".equalsIgnoreCase(existing.getScheduleType()) && req.getWindowStartTime() == null)) {
            existing.setWindowStartTime(req.getWindowStartTime());
        }
        if (req.getWindowEndTime() != null ||
            ("FLEXIBLE".equalsIgnoreCase(existing.getScheduleType()) && req.getWindowEndTime() == null)) {
            existing.setWindowEndTime(req.getWindowEndTime());
        }
        if (req.getRequiredDurationMinutes() != null ||
            ("FLEXIBLE".equalsIgnoreCase(existing.getScheduleType()) && req.getRequiredDurationMinutes() == null)) {
            existing.setRequiredDurationMinutes(req.getRequiredDurationMinutes());
        }
        if (req.getRequiredStaffCount() != null) existing.setRequiredStaffCount(req.getRequiredStaffCount());
        if (req.getLane() != null) existing.setLane(req.getLane());
        if (req.getMustBeContiguous() != null) existing.setMustBeContiguous(req.getMustBeContiguous());
        if (req.getEffectiveFrom() != null) existing.setEffectiveFrom(req.getEffectiveFrom());
        if (req.getEffectiveTo() != null) existing.setEffectiveTo(req.getEffectiveTo());
        if (req.getPriority() != null) existing.setPriority(req.getPriority());
        if (req.getNote() != null) existing.setNote(req.getNote());
        if (req.getActive() != null) existing.setActive(req.getActive());
        repository.update(existing);
        return ResponseEntity.ok().build();
    }

    private void validateCommon(String storeCode, String taskCode, String scheduleType) {
        if (storeCode == null || storeCode.isBlank()) {
            throw new IllegalArgumentException("storeCode is required");
        }
        if (taskCode == null || taskCode.isBlank()) {
            throw new IllegalArgumentException("taskCode is required");
        }
        if (scheduleType == null || (!scheduleType.equals("FIXED") && !scheduleType.equals("FLEXIBLE"))) {
            throw new IllegalArgumentException("scheduleType must be FIXED or FLEXIBLE");
        }
    }

    private MonthlyTaskPlan toPlan(String storeCode, String departmentCode, String taskCode, String scheduleType,
                                   Date fixedStartTime, Date fixedEndTime, Date windowStartTime, Date windowEndTime,
                                   Integer requiredDurationMinutes, Integer requiredStaffCount, Integer lane, 
                                   Short mustBeContiguous, Date effectiveFrom, Date effectiveTo, 
                                   Integer priority, String note, Boolean active) {
        MonthlyTaskPlan plan = new MonthlyTaskPlan();
        plan.setStoreCode(storeCode);
        plan.setDepartmentCode(departmentCode);
        plan.setTaskCode(taskCode);
        plan.setScheduleType(scheduleType);
        plan.setFixedStartTime(fixedStartTime);
        plan.setFixedEndTime(fixedEndTime);
        plan.setWindowStartTime(windowStartTime);
        plan.setWindowEndTime(windowEndTime);
        plan.setRequiredDurationMinutes(requiredDurationMinutes);
        plan.setRequiredStaffCount(requiredStaffCount);
        plan.setLane(lane);
        plan.setMustBeContiguous(mustBeContiguous);
        plan.setEffectiveFrom(effectiveFrom);
        plan.setEffectiveTo(effectiveTo);
        plan.setPriority(priority);
        plan.setNote(note);
        plan.setActive(active);
        return plan;
    }
}
