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
    List<TaskPlan> listWeeklyEffective(String storeCode, short dayOfWeek, Date date);
    List<TaskPlan> listSpecialByStoreAndRange(String storeCode, Date from, Date to);

    List<TaskPlan> selectSpecialByStoreAndDate(String storeCode, Date specialDate);

    List<Date> listSpecialDatesByStore(String storeCode);
}
