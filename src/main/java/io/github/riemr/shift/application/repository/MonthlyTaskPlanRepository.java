package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.infrastructure.persistence.entity.MonthlyTaskPlan;

import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface MonthlyTaskPlanRepository {
    void save(MonthlyTaskPlan p);
    void update(MonthlyTaskPlan p);
    void delete(Long planId);
    void deleteById(Long planId);
    MonthlyTaskPlan find(Long planId);
    Optional<MonthlyTaskPlan> findById(Long planId);

    void replaceDomDays(Long planId, List<Short> daysOfMonth);
    void replaceWomPairs(Long planId, List<Short> weeksOfMonth, List<Short> daysOfWeek);

    List<MonthlyTaskPlan> listEffectiveByStoreAndDate(String storeCode, Date date);
}

