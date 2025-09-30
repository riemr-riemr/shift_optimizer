package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.repository.TaskRepository;
import io.github.riemr.shift.application.repository.TaskPlanRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.Task;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class TaskPlanService {
    private final TaskPlanRepository planRepository;
    private final TaskRepository taskRepository;

    public TaskPlanService(TaskPlanRepository planRepository,
                           TaskRepository taskRepository) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
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
        List<TaskPlan> specials = planRepository.listSpecialByStoreAndRange(storeCode, fromDate, toDate);
        Map<LocalDate, List<TaskPlan>> specialsByDate = new HashMap<>();
        for (TaskPlan s : specials) {
            LocalDate d = s.getSpecialDate().toInstant().atZone(zone).toLocalDate();
            specialsByDate.computeIfAbsent(d, k -> new ArrayList<>()).add(s);
        }

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            short dow = (short) d.getDayOfWeek().getValue(); // ISO 1..7
            List<TaskPlan> plans = planRepository.listWeeklyEffective(storeCode, dow, Date.from(d.atStartOfDay(zone).toInstant()));

            for (TaskPlan p : plans) {
                created += createTasksFromPlan(storeCode, d, p, createdBy);
            }
            for (TaskPlan s : specialsByDate.getOrDefault(d, Collections.emptyList())) {
                created += createTasksFromSpecial(storeCode, d, s, createdBy);
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

    private int createTasksFromSpecial(String storeCode, LocalDate date, TaskPlan s, String createdBy) {
        if ("FIXED".equals(s.getScheduleType())) {
            int count = Math.max(1, nvl(s.getRequiredStaffCount(), 1));
            for (int i = 0; i < count; i++) {
                Task t = new Task();
                t.setStoreCode(storeCode);
                t.setWorkDate(toDate(date));
                t.setName(s.getTaskCode());
                t.setDescription("Special:" + s.getTaskCode());
                t.setScheduleType("FIXED");
                t.setFixedStartAt(toDate(date.atTime(toLocalTime(s.getFixedStartTime()))));
                t.setFixedEndAt(toDate(date.atTime(toLocalTime(s.getFixedEndTime()))));
                t.setRequiredStaffCount(1);
                t.setPriority(s.getPriority());
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
            t.setName(s.getTaskCode());
            t.setDescription("Special:" + s.getTaskCode());
            t.setScheduleType("FLEXIBLE");
            t.setWindowStartAt(toDate(date.atTime(toLocalTime(s.getWindowStartTime()))));
            t.setWindowEndAt(toDate(date.atTime(toLocalTime(s.getWindowEndTime()))));
            t.setRequiredDurationMinutes(s.getRequiredDurationMinutes());
            t.setPriority(s.getPriority());
            t.setMustBeContiguous(s.getMustBeContiguous());
            t.setCreatedBy(createdBy);
            t.setCreatedAt(new Date());
            t.setUpdatedBy(createdBy);
            t.setUpdatedAt(new Date());
            taskRepository.save(t);
            return 1;
        }
    }

    private static String resolveType(TaskPlan p) { return p.getScheduleType() != null ? p.getScheduleType() : "FIXED"; }
    private static int nvl(Integer v, int def) { return v == null ? def : v; }
    private static java.util.Date toDate(LocalDate d) { return java.util.Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()); }
    private static java.util.Date toDate(LocalDateTime dt) { return java.util.Date.from(dt.atZone(ZoneId.systemDefault()).toInstant()); }
    private static LocalTime toLocalTime(java.util.Date timeOnly) { return timeOnly.toInstant().atZone(ZoneId.systemDefault()).toLocalTime(); }
}
