package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;

import java.util.Date;
import java.util.List;

public interface TaskPlanRepository {
    void save(TaskPlan p);
    void update(TaskPlan p);
    void delete(Long planId);
    TaskPlan find(Long planId);
    List<TaskPlan> listWeeklyByStoreAndDow(String storeCode, short dayOfWeek);
    List<TaskPlan> listWeeklyByStoreAndDowAndDept(String storeCode, short dayOfWeek, String departmentCode);
    List<TaskPlan> listWeeklyEffective(String storeCode, short dayOfWeek, Date date);

    // Special-day plan APIs removed; use monthly_task_plan for date-based patterns.

    // Deletions for replace mode during copy
    void deleteWeeklyByStoreDeptAndDow(String storeCode, String departmentCode, short dayOfWeek);
}
