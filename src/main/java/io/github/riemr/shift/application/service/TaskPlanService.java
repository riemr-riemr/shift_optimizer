package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.repository.TaskRepository;
import io.github.riemr.shift.application.repository.MonthlyTaskPlanRepository;
import io.github.riemr.shift.application.repository.TaskPlanRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.Task;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import io.github.riemr.shift.infrastructure.persistence.entity.MonthlyTaskPlan;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentTaskAssignment;
import io.github.riemr.shift.infrastructure.mapper.DepartmentTaskAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.WorkDemandIntervalMapper;
import io.github.riemr.shift.application.dto.DemandIntervalDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class TaskPlanService {
    private final TaskPlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final MonthlyTaskPlanRepository monthlyRepository;
    private final DepartmentTaskAssignmentMapper deptTaskAssignmentMapper;
    private final WorkDemandIntervalMapper workDemandIntervalMapper;

    public TaskPlanService(TaskPlanRepository planRepository,
                           TaskRepository taskRepository,
                           MonthlyTaskPlanRepository monthlyRepository,
                           DepartmentTaskAssignmentMapper deptTaskAssignmentMapper,
                           WorkDemandIntervalMapper workDemandIntervalMapper) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.monthlyRepository = monthlyRepository;
        this.deptTaskAssignmentMapper = deptTaskAssignmentMapper;
        this.workDemandIntervalMapper = workDemandIntervalMapper;
    }

    @Transactional
    public int copyFromCurrentView(String storeCode,
                                   String departmentCode,
                                   String mode,
                                   Short sourceDayOfWeek,
                                   LocalDate sourceDate,
                                   List<Short> targetDaysOfWeek,
                                   List<LocalDate> targetDates,
                                   boolean replaceExisting) {
        Objects.requireNonNull(storeCode, "storeCode is required");
        Objects.requireNonNull(departmentCode, "departmentCode is required");
        // Only weekly mode is supported (special removed)

        List<TaskPlan> sourcePlans;
        if (sourceDayOfWeek == null) throw new IllegalArgumentException("dayOfWeek required for weekly mode");
        sourcePlans = planRepository.listWeeklyByStoreAndDowAndDept(storeCode, sourceDayOfWeek, departmentCode);

        int created = 0;
        // Copy to weekly targets
        if (targetDaysOfWeek != null) {
            for (Short dow : targetDaysOfWeek) {
                if (dow == null) continue;
                if (replaceExisting) {
                    planRepository.deleteWeeklyByStoreDeptAndDow(storeCode, departmentCode, dow);
                }
                for (TaskPlan p : sourcePlans) {
                    TaskPlan copy = clonePlan(p);
                    copy.setPlanId(null);
                    copy.setDayOfWeek(dow);
                    planRepository.save(copy);
                    created++;
                }
            }
        }
        return created;
    }

    /**
     * 週次・月次の作業計画から、指定範囲の DepartmentTaskAssignment を再生成する（従業員未割当）。
     * - FIXED: 指定開始/終了で requiredStaffCount 件を作成
     * - FLEXIBLE: 窓全体を1件（requiredStaffCount 件）として作成（所要は反映せず）
     */
    @Transactional
    public int materializeDepartmentAssignments(String storeCode,
                                                String departmentCode,
                                                java.time.LocalDate from,
                                                java.time.LocalDate to,
                                                String createdBy) {
        if (storeCode == null || storeCode.isBlank() || departmentCode == null || departmentCode.isBlank()) return 0;
        // まず既存を削除（半開区間）
        deptTaskAssignmentMapper.deleteByMonthStoreAndDepartment(from, to, storeCode, departmentCode);
        int created = 0;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        for (java.time.LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            short dow = (short) d.getDayOfWeek().getValue();
            java.util.Date dd = java.util.Date.from(d.atStartOfDay(zone).toInstant());
            // 週次有効
            var weekly = planRepository.listWeeklyEffective(storeCode, dow, dd);
            // 月次（DOM/WOM）有効
            var monthly = monthlyRepository.listEffectiveByStoreAndDate(storeCode, dd);
            // 部門でフィルタ
            weekly = weekly.stream().filter(p -> departmentCode.equals(p.getDepartmentCode())).toList();
            monthly = monthly.stream().filter(p -> departmentCode.equals(p.getDepartmentCode())).toList();

            // 週次
            for (TaskPlan p : weekly) {
                created += toDeptAssignments(storeCode, departmentCode, p.getTaskCode(), p.getScheduleType(),
                        toLocalTime(p.getFixedStartTime()), toLocalTime(p.getFixedEndTime()),
                        toLocalTime(p.getWindowStartTime()), toLocalTime(p.getWindowEndTime()),
                        p.getRequiredStaffCount(), d, createdBy);
            }
            // 月次
            for (MonthlyTaskPlan p : monthly) {
                created += toDeptAssignments(storeCode, departmentCode, p.getTaskCode(), p.getScheduleType(),
                        toLocalTime(p.getFixedStartTime()), toLocalTime(p.getFixedEndTime()),
                        toLocalTime(p.getWindowStartTime()), toLocalTime(p.getWindowEndTime()),
                        p.getRequiredStaffCount(), d, createdBy);
            }
        }
        return created;
    }

    private int toDeptAssignments(String storeCode, String departmentCode, String taskCode, String scheduleType,
                                  java.time.LocalTime fixedStart, java.time.LocalTime fixedEnd,
                                  java.time.LocalTime winStart, java.time.LocalTime winEnd,
                                  Integer requiredStaff, java.time.LocalDate date, String createdBy) {
        int count = Math.max(1, nvl(requiredStaff, 1));
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.util.Date startAt;
        java.util.Date endAt;
        if ("FIXED".equalsIgnoreCase(scheduleType) && fixedStart != null && fixedEnd != null) {
            startAt = java.util.Date.from(date.atTime(fixedStart).atZone(zone).toInstant());
            endAt = java.util.Date.from(date.atTime(fixedEnd).atZone(zone).toInstant());
        } else if (winStart != null && winEnd != null) {
            startAt = java.util.Date.from(date.atTime(winStart).atZone(zone).toInstant());
            endAt = java.util.Date.from(date.atTime(winEnd).atZone(zone).toInstant());
        } else {
            return 0;
        }
        int created = 0;
        for (int i = 0; i < count; i++) {
            DepartmentTaskAssignment a = new DepartmentTaskAssignment();
            a.setStoreCode(storeCode);
            a.setDepartmentCode(departmentCode);
            a.setTaskCode(taskCode);
            a.setEmployeeCode(null);
            a.setStartAt(startAt);
            a.setEndAt(endAt);
            a.setCreatedBy(createdBy);
            deptTaskAssignmentMapper.insert(a);
            created++;
        }
        return created;
    }
    private TaskPlan clonePlan(TaskPlan p) {
        TaskPlan c = new TaskPlan();
        c.setStoreCode(p.getStoreCode());
        c.setDepartmentCode(p.getDepartmentCode());
        c.setTaskCode(p.getTaskCode());
        c.setScheduleType(p.getScheduleType());
        c.setFixedStartTime(p.getFixedStartTime());
        c.setFixedEndTime(p.getFixedEndTime());
        c.setWindowStartTime(p.getWindowStartTime());
        c.setWindowEndTime(p.getWindowEndTime());
        c.setRequiredDurationMinutes(p.getRequiredDurationMinutes());
        c.setRequiredStaffCount(p.getRequiredStaffCount());
        c.setLane(p.getLane());
        c.setMustBeContiguous(p.getMustBeContiguous());
        c.setEffectiveFrom(p.getEffectiveFrom());
        c.setEffectiveTo(p.getEffectiveTo());
        c.setPriority(p.getPriority());
        c.setNote(p.getNote());
        c.setActive(p.getActive());
        return c;
    }

    @Transactional
    public int applyReplacing(String storeCode, LocalDate from, LocalDate to, String createdBy) {
        Date fromDate = Date.from(from.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date toDate = Date.from(to.atStartOfDay(ZoneId.systemDefault()).toInstant());
        taskRepository.deleteByStoreAndDateRange(storeCode, fromDate, toDate);
        return generate(storeCode, from, to, createdBy);
    }

    @Transactional
    public int generate(String storeCode, LocalDate from, LocalDate to, String createdBy) {
        int created = 0;
        ZoneId zone = ZoneId.systemDefault();
        Date fromDate = Date.from(from.atStartOfDay(zone).toInstant());
        Date toDate = Date.from(to.atStartOfDay(zone).toInstant());

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            short dow = (short) d.getDayOfWeek().getValue(); // ISO 1..7
            List<TaskPlan> plans = planRepository.listWeeklyEffective(storeCode, dow, Date.from(d.atStartOfDay(zone).toInstant()));

            for (TaskPlan p : plans) {
                created += createTasksFromPlan(storeCode, d, p, createdBy);
            }
            // Monthly (DOM/WOM) plans
            List<MonthlyTaskPlan> monthly = monthlyRepository.listEffectiveByStoreAndDate(storeCode, Date.from(d.atStartOfDay(zone).toInstant()));
            for (MonthlyTaskPlan mp : monthly) {
                created += createTasksFromMonthly(storeCode, d, mp, createdBy);
            }
        }
        return created;
    }

    private int createTasksFromPlan(String storeCode, LocalDate date, TaskPlan p, String createdBy) {
        if ("FIXED".equals(resolveType(p))) {
            int count = Math.max(1, nvl(p.getRequiredStaffCount(), 1));
            for (int i = 0; i < count; i++) {
                Task t = new Task();
                t.setStoreCode(storeCode);
                t.setWorkDate(toDate(date));
                t.setName(p.getTaskCode());
                t.setDescription("Weekly:" + p.getTaskCode());
                t.setScheduleType("FIXED");
                t.setFixedStartAt(toDate(date.atTime(toLocalTime(p.getFixedStartTime()))));
                t.setFixedEndAt(toDate(date.atTime(toLocalTime(p.getFixedEndTime()))));
                t.setRequiredStaffCount(1);
                t.setPriority(p.getPriority());
                t.setCreatedBy(createdBy);
                t.setCreatedAt(new Date());
                t.setUpdatedBy(createdBy);
                t.setUpdatedAt(new Date());
                taskRepository.save(t);
            }
            return count;
        } else {
            Task t = new Task();
            t.setStoreCode(storeCode);
            t.setWorkDate(toDate(date));
            t.setName(p.getTaskCode());
            t.setDescription("Weekly:" + p.getTaskCode());
            t.setScheduleType("FLEXIBLE");
            t.setWindowStartAt(toDate(date.atTime(toLocalTime(p.getWindowStartTime()))));
            t.setWindowEndAt(toDate(date.atTime(toLocalTime(p.getWindowEndTime()))));
            t.setRequiredDurationMinutes(p.getRequiredDurationMinutes());
            t.setPriority(p.getPriority());
            t.setMustBeContiguous(p.getMustBeContiguous());
            t.setCreatedBy(createdBy);
            t.setCreatedAt(new Date());
            t.setUpdatedBy(createdBy);
            t.setUpdatedAt(new Date());
            taskRepository.save(t);
            return 1;
        }
    }

    /**
     * 週次・月次の作業計画から、work_demand_interval を再生成（指定範囲/店舗/部門）。
     * demand には requiredStaffCount を使用。FLEXIBLE は窓全体を1区間として扱う。
     */
    @Transactional
    public int materializeWorkDemands(String storeCode, String departmentCode,
                                      java.time.LocalDate from, java.time.LocalDate to) {
        if (storeCode == null || storeCode.isBlank() || departmentCode == null || departmentCode.isBlank()) return 0;
        workDemandIntervalMapper.deleteByStoreDeptAndRange(storeCode, departmentCode, from, to);
        int created = 0;
        for (java.time.LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            short dow = (short) d.getDayOfWeek().getValue();
            java.util.Date dd = java.util.Date.from(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
            var weekly = planRepository.listWeeklyEffective(storeCode, dow, dd)
                    .stream().filter(p -> departmentCode.equals(p.getDepartmentCode())).toList();
            var monthly = monthlyRepository.listEffectiveByStoreAndDate(storeCode, dd)
                    .stream().filter(p -> departmentCode.equals(p.getDepartmentCode())).toList();
            for (TaskPlan p : weekly) {
                created += toWorkDemandRows(storeCode, departmentCode, d, p.getTaskCode(), p.getScheduleType(),
                        toLocalTime(p.getFixedStartTime()), toLocalTime(p.getFixedEndTime()),
                        toLocalTime(p.getWindowStartTime()), toLocalTime(p.getWindowEndTime()),
                        p.getRequiredStaffCount());
            }
            for (MonthlyTaskPlan p : monthly) {
                created += toWorkDemandRows(storeCode, departmentCode, d, p.getTaskCode(), p.getScheduleType(),
                        toLocalTime(p.getFixedStartTime()), toLocalTime(p.getFixedEndTime()),
                        toLocalTime(p.getWindowStartTime()), toLocalTime(p.getWindowEndTime()),
                        p.getRequiredStaffCount());
            }
        }
        return created;
    }

    private int toWorkDemandRows(String storeCode, String departmentCode, java.time.LocalDate date, String taskCode,
                                 String scheduleType, java.time.LocalTime fixedStart, java.time.LocalTime fixedEnd,
                                 java.time.LocalTime winStart, java.time.LocalTime winEnd, Integer requiredStaff) {
        int demand = Math.max(1, nvl(requiredStaff, 1));
        java.time.LocalTime from;
        java.time.LocalTime to;
        if ("FIXED".equalsIgnoreCase(scheduleType) && fixedStart != null && fixedEnd != null) {
            from = fixedStart; to = fixedEnd;
        } else if (winStart != null && winEnd != null) {
            from = winStart; to = winEnd;
        } else { return 0; }
        DemandIntervalDto dto = DemandIntervalDto.builder()
                .storeCode(storeCode)
                .departmentCode(departmentCode)
                .targetDate(date)
                .from(from)
                .to(to)
                .demand(demand)
                .taskCode(taskCode)
                .build();
        workDemandIntervalMapper.insert(dto);
        return 1;
    }
    // Special-day generation removed; use monthly_task_plan

    private int createTasksFromMonthly(String storeCode, LocalDate date, MonthlyTaskPlan p, String createdBy) {
        if ("FIXED".equals(resolveType(p.getScheduleType()))) {
            int count = Math.max(1, nvl(p.getRequiredStaffCount(), 1));
            for (int i = 0; i < count; i++) {
                Task t = new Task();
                t.setStoreCode(storeCode);
                t.setWorkDate(toDate(date));
                t.setName(p.getTaskCode());
                t.setDescription("Monthly:" + p.getTaskCode());
                t.setScheduleType("FIXED");
                t.setFixedStartAt(toDate(date.atTime(toLocalTime(p.getFixedStartTime()))));
                t.setFixedEndAt(toDate(date.atTime(toLocalTime(p.getFixedEndTime()))));
                t.setRequiredStaffCount(1);
                t.setPriority(p.getPriority());
                t.setCreatedBy(createdBy);
                t.setCreatedAt(new Date());
                t.setUpdatedBy(createdBy);
                t.setUpdatedAt(new Date());
                taskRepository.save(t);
            }
            return count;
        } else {
            Task t = new Task();
            t.setStoreCode(storeCode);
            t.setWorkDate(toDate(date));
            t.setName(p.getTaskCode());
            t.setDescription("Monthly:" + p.getTaskCode());
            t.setScheduleType("FLEXIBLE");
            t.setWindowStartAt(toDate(date.atTime(toLocalTime(p.getWindowStartTime()))));
            t.setWindowEndAt(toDate(date.atTime(toLocalTime(p.getWindowEndTime()))));
            t.setRequiredDurationMinutes(p.getRequiredDurationMinutes());
            t.setPriority(p.getPriority());
            t.setMustBeContiguous(p.getMustBeContiguous());
            t.setCreatedBy(createdBy);
            t.setCreatedAt(new Date());
            t.setUpdatedBy(createdBy);
            t.setUpdatedAt(new Date());
            taskRepository.save(t);
            return 1;
        }
    }

    private static String resolveType(TaskPlan p) { return p.getScheduleType() != null ? p.getScheduleType() : "FIXED"; }
    private static String resolveType(String scheduleType) { return scheduleType != null ? scheduleType : "FIXED"; }
    private static int nvl(Integer v, int def) { return v == null ? def : v; }
    private static java.util.Date toDate(LocalDate d) { return java.util.Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()); }
    private static java.util.Date toDate(LocalDateTime dt) { return java.util.Date.from(dt.atZone(ZoneId.systemDefault()).toInstant()); }
    private static LocalTime toLocalTime(java.util.Date timeOnly) { return timeOnly.toInstant().atZone(ZoneId.systemDefault()).toLocalTime(); }
}
