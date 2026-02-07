package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.MonthlyTaskPlanRepository;
import io.github.riemr.shift.infrastructure.mapper.MonthlyTaskPlanMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.MonthlyTaskPlan;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public class MonthlyTaskPlanRepositoryImpl implements MonthlyTaskPlanRepository {
    private final MonthlyTaskPlanMapper mapper;

    public MonthlyTaskPlanRepositoryImpl(MonthlyTaskPlanMapper mapper) { this.mapper = mapper; }

    @Override public void save(MonthlyTaskPlan p) { mapper.insert(p); }
    @Override public void update(MonthlyTaskPlan p) { mapper.updateByPrimaryKey(p); }
    @Override public void delete(Long planId) { mapper.deleteByPrimaryKey(planId); }
    @Override public void deleteById(Long planId) { mapper.deleteByPrimaryKey(planId); }
    @Override public MonthlyTaskPlan find(Long planId) { return mapper.selectByPrimaryKey(planId); }
    @Override public Optional<MonthlyTaskPlan> findById(Long planId) { 
        MonthlyTaskPlan plan = mapper.selectByPrimaryKey(planId);
        return Optional.ofNullable(plan);
    }

    @Override
    public void replaceDomDays(Long planId, List<Short> daysOfMonth) {
        mapper.deleteDomByPlan(planId);
        if (daysOfMonth != null) {
            for (Short d : daysOfMonth) {
                if (d == null) continue;
                mapper.insertDom(planId, d);
            }
        }
    }

    @Override
    public void replaceWomPairs(Long planId, List<Short> weeksOfMonth, List<Short> daysOfWeek) {
        mapper.deleteWomByPlan(planId);
        if (weeksOfMonth == null || daysOfWeek == null) return;
        for (Short w : weeksOfMonth) {
            if (w == null) continue;
            for (Short dow : daysOfWeek) {
                if (dow == null) continue;
                mapper.insertWom(planId, w, dow);
            }
        }
    }

    @Override
    public List<MonthlyTaskPlan> listEffectiveByStoreAndDate(String storeCode, Date date) {
        return mapper.selectEffectiveByStoreAndDate(storeCode, date);
    }
}
