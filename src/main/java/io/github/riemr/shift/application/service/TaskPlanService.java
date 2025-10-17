package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.repository.TaskRepository;
import io.github.riemr.shift.application.repository.MonthlyTaskPlanRepository;
import io.github.riemr.shift.application.repository.TaskPlanRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.Task;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import io.github.riemr.shift.infrastructure.persistence.entity.MonthlyTaskPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class TaskPlanService {
    private final TaskPlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final MonthlyTaskPlanRepository monthlyRepository;

    public TaskPlanService(TaskPlanRepository planRepository,
                           TaskRepository taskRepository,
                           MonthlyTaskPlanRepository monthlyRepository) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.monthlyRepository = monthlyRepository;
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
