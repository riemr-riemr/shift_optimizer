package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.TaskPlanRepository;
import io.github.riemr.shift.infrastructure.mapper.TaskPlanMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public class TaskPlanRepositoryImpl implements TaskPlanRepository {
    private final TaskPlanMapper mapper;

    public TaskPlanRepositoryImpl(TaskPlanMapper mapper) { this.mapper = mapper; }

    @Override public void save(TaskPlan p) { mapper.insert(p); }
    @Override public void update(TaskPlan p) { mapper.updateByPrimaryKey(p); }
    @Override public void delete(Long planId) { mapper.deleteByPrimaryKey(planId); }
    @Override public TaskPlan find(Long planId) { return mapper.selectByPrimaryKey(planId); }
    @Override public List<TaskPlan> listWeeklyByStoreAndDow(String storeCode, short dayOfWeek) {
        return mapper.selectWeeklyByStoreAndDow(storeCode, dayOfWeek);
    }
    @Override public List<TaskPlan> listWeeklyByStoreAndDowAndDept(String storeCode, short dayOfWeek, String departmentCode) {
        return mapper.selectWeeklyByStoreAndDowAndDept(storeCode, dayOfWeek, departmentCode);
    }
    @Override public List<TaskPlan> listWeeklyEffective(String storeCode, short dayOfWeek, Date date) {
        return mapper.selectWeeklyEffective(storeCode, dayOfWeek, date);
    }
    @Override public List<TaskPlan> listSpecialByStoreAndRange(String storeCode, Date from, Date to) {
        return mapper.selectSpecialByStoreAndRange(storeCode, from, to);
    }

    @Override public List<TaskPlan> selectSpecialByStoreAndDate(String storeCode, Date specialDate) {
        return mapper.selectSpecialByStoreAndDate(storeCode, specialDate);
    }
    @Override public List<TaskPlan> selectSpecialByStoreAndDateAndDept(String storeCode, Date specialDate, String departmentCode) {
        return mapper.selectSpecialByStoreAndDateAndDept(storeCode, specialDate, departmentCode);
    }

    @Override public List<Date> listSpecialDatesByStore(String storeCode) {
        return mapper.selectSpecialDatesByStore(storeCode);
    }

    @Override
    public void deleteWeeklyByStoreDeptAndDow(String storeCode, String departmentCode, short dayOfWeek) {
        mapper.deleteWeeklyByStoreDeptAndDow(storeCode, departmentCode, dayOfWeek);
    }

    @Override
    public void deleteSpecialByStoreDeptAndDate(String storeCode, String departmentCode, Date specialDate) {
        mapper.deleteSpecialByStoreDeptAndDate(storeCode, departmentCode, specialDate);
    }
}
