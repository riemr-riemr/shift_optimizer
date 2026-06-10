package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.repository.MonthlyTaskPlanRepository;
import io.github.riemr.shift.application.repository.TaskPlanRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import io.github.riemr.shift.infrastructure.persistence.entity.MonthlyTaskPlan;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentTaskAssignment;
import io.github.riemr.shift.infrastructure.mapper.DepartmentTaskAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.WorkDemandIntervalMapper;
import io.github.riemr.shift.application.dto.DemandIntervalDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskPlanService {
    private final TaskPlanRepository planRepository;
    private final MonthlyTaskPlanRepository monthlyRepository;
    private final DepartmentTaskAssignmentMapper deptTaskAssignmentMapper;
    private final WorkDemandIntervalMapper workDemandIntervalMapper;

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int materializeDepartmentAssignments(String storeCode,
                                                String departmentCode,
                                                LocalDate from,
                                                LocalDate to,
                                                String createdBy) {
        if (storeCode == null || storeCode.isBlank() || departmentCode == null || departmentCode.isBlank()) return 0;
        // まず既存を削除（半開区間）
        deptTaskAssignmentMapper.deleteByMonthStoreAndDepartment(from, to, storeCode, departmentCode);
        int created = 0;
        ZoneId zone = ZoneId.systemDefault();
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            short dow = (short) d.getDayOfWeek().getValue();
            Date dd = Date.from(d.atStartOfDay(zone).toInstant());
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
                                  LocalTime fixedStart, LocalTime fixedEnd,
                                  LocalTime winStart, LocalTime winEnd,
                                  Integer requiredStaff, LocalDate date, String createdBy) {
        int count = Math.max(1, nvl(requiredStaff, 1));
        ZoneId zone = ZoneId.systemDefault();
        Date startAt;
        Date endAt;
        if ("FIXED".equalsIgnoreCase(scheduleType) && fixedStart != null && fixedEnd != null) {
            startAt = Date.from(date.atTime(fixedStart).atZone(zone).toInstant());
            endAt = Date.from(date.atTime(fixedEnd).atZone(zone).toInstant());
        } else if (winStart != null && winEnd != null) {
            startAt = Date.from(date.atTime(winStart).atZone(zone).toInstant());
            endAt = Date.from(date.atTime(winEnd).atZone(zone).toInstant());
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


    /**
     * 週次・月次の作業計画から、work_demand_interval を再生成（指定範囲/店舗/部門）。
     * demand には requiredStaffCount を使用。FLEXIBLE は窓全体を1区間として扱う。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int materializeWorkDemands(String storeCode, String departmentCode,
                                      LocalDate from, LocalDate to) {
        if (storeCode == null || storeCode.isBlank() || departmentCode == null || departmentCode.isBlank()) {
            log.debug("materializeWorkDemands - invalid parameters, returning 0");
            return 0;
        }
        log.debug("materializeWorkDemands starting for store: {}, dept: {}, range: {} to {}", storeCode, departmentCode, from, to);
        workDemandIntervalMapper.deleteByStoreDeptAndRange(storeCode, departmentCode, from, to);
        int created = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            short dow = (short) d.getDayOfWeek().getValue();
            Date dd = Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
            var weekly = planRepository.listWeeklyEffective(storeCode, dow, dd)
                    .stream().filter(p -> departmentCode.equals(p.getDepartmentCode())).toList();
            var monthly = monthlyRepository.listEffectiveByStoreAndDate(storeCode, dd)
                    .stream().filter(p -> departmentCode.equals(p.getDepartmentCode())).toList();
            
            log.debug("Date {} (dow={}) for dept {} - weekly: {}, monthly: {}", d, dow, departmentCode, weekly.size(), monthly.size());
            for (TaskPlan p : weekly) {
                log.debug("Processing weekly plan - taskCode: {}, scheduleType: {}", p.getTaskCode(), p.getScheduleType());
                int result = toWorkDemandRows(storeCode, departmentCode, d, p.getTaskCode(), p.getScheduleType(),
                        toLocalTime(p.getFixedStartTime()), toLocalTime(p.getFixedEndTime()),
                        toLocalTime(p.getWindowStartTime()), toLocalTime(p.getWindowEndTime()),
                        p.getRequiredStaffCount(), p.getLane());
                created += result;
                log.debug("Weekly plan processing result: {}", result);
            }
            for (MonthlyTaskPlan p : monthly) {
                created += toWorkDemandRows(storeCode, departmentCode, d, p.getTaskCode(), p.getScheduleType(),
                        toLocalTime(p.getFixedStartTime()), toLocalTime(p.getFixedEndTime()),
                        toLocalTime(p.getWindowStartTime()), toLocalTime(p.getWindowEndTime()),
                        p.getRequiredStaffCount(), p.getLane());
            }
        }
        log.debug("materializeWorkDemands completed - created {} work demand intervals for dept: {}", created, departmentCode);
        return created;
    }

    /**
     * 全部門の作業計画からwork_demand_intervalを物質化
     * 部門未指定時の最適化で使用
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int materializeWorkDemandsForAllDepartments(String storeCode, 
                                                       LocalDate from, 
                                                       LocalDate to) {
        if (storeCode == null || storeCode.isBlank()) return 0;
        
        // 既存のwork_demand_intervalを全削除（店舗・期間指定）
        workDemandIntervalMapper.deleteByStoreAndRange(storeCode, from, to);
        
        int totalCreated = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            short dow = (short) d.getDayOfWeek().getValue();
            Date dd = Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
            
            // 全部門の週次計画を取得
            var weekly = planRepository.listWeeklyEffective(storeCode, dow, dd);
            // 全部門の月次計画を取得
            var monthly = monthlyRepository.listEffectiveByStoreAndDate(storeCode, dd);
            
            log.debug("Date {} (dow={}) - weekly plans: {}, monthly plans: {}", d, dow, weekly.size(), monthly.size());
            
            for (TaskPlan p : weekly) {
                if (p.getDepartmentCode() != null && !p.getDepartmentCode().isBlank()) {
                    totalCreated += toWorkDemandRows(storeCode, p.getDepartmentCode(), d, p.getTaskCode(), p.getScheduleType(),
                            toLocalTime(p.getFixedStartTime()), toLocalTime(p.getFixedEndTime()),
                            toLocalTime(p.getWindowStartTime()), toLocalTime(p.getWindowEndTime()),
                            p.getRequiredStaffCount(), p.getLane());
                }
            }
            for (MonthlyTaskPlan p : monthly) {
                if (p.getDepartmentCode() != null && !p.getDepartmentCode().isBlank()) {
                    totalCreated += toWorkDemandRows(storeCode, p.getDepartmentCode(), d, p.getTaskCode(), p.getScheduleType(),
                            toLocalTime(p.getFixedStartTime()), toLocalTime(p.getFixedEndTime()),
                            toLocalTime(p.getWindowStartTime()), toLocalTime(p.getWindowEndTime()),
                            p.getRequiredStaffCount(), p.getLane());
                }
            }
        }
        log.debug("Total work demand intervals created: {}", totalCreated);
        return totalCreated;
    }

    private int toWorkDemandRows(String storeCode, String departmentCode, LocalDate date, String taskCode,
                                 String scheduleType, LocalTime fixedStart, LocalTime fixedEnd,
                                 LocalTime winStart, LocalTime winEnd, Integer requiredStaff, Integer lane) {
        log.debug("toWorkDemandRows - task: {}, scheduleType: {}, fixedStart: {}, fixedEnd: {}, winStart: {}, winEnd: {}",
                taskCode, scheduleType, fixedStart, fixedEnd, winStart, winEnd);

        int demand = Math.max(1, nvl(requiredStaff, 1));
        LocalTime from;
        LocalTime to;
        if ("FIXED".equalsIgnoreCase(scheduleType) && fixedStart != null && fixedEnd != null) {
            from = fixedStart; to = fixedEnd;
        } else if (winStart != null && winEnd != null) {
            from = winStart; to = winEnd;
        } else {
            log.debug("No valid time range found for task: {}, returning 0", taskCode);
            return 0;
        }

        try {
            DemandIntervalDto dto = DemandIntervalDto.builder()
                    .storeCode(storeCode)
                    .departmentCode(departmentCode)
                    .targetDate(date)
                    .from(from)
                    .to(to)
                    .demand(demand)
                    .taskCode(taskCode)
                    .lane(lane)
                    .build();
            workDemandIntervalMapper.insert(dto);
            return 1;
        } catch (Exception e) {
            log.error("Failed to insert work demand interval for task: {}", taskCode, e);
            return 0;
        }
    }
    // Special-day generation removed; use monthly_task_plan

    private static int nvl(Integer v, int def) { return v == null ? def : v; }
    private static LocalTime toLocalTime(Date timeOnly) { 
        if (timeOnly == null) return null;
        return timeOnly.toInstant().atZone(ZoneId.systemDefault()).toLocalTime(); 
    }
}
