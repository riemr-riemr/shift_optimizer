package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.TaskMasterRepository;
import io.github.riemr.shift.infrastructure.mapper.TaskMasterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TaskMasterRepositoryImpl implements TaskMasterRepository {
    private final TaskMasterMapper mapper;

    public TaskMasterRepositoryImpl(TaskMasterMapper mapper) { this.mapper = mapper; }

    @Override public void save(TaskMaster m) { mapper.insert(m); }
    @Override public void update(TaskMaster m) { mapper.updateByPrimaryKey(m); }
    @Override public void delete(String taskCode, String departmentCode) { mapper.deleteByPrimaryKey(taskCode, departmentCode); }
    @Override public TaskMaster find(String taskCode, String departmentCode) { return mapper.selectByPrimaryKey(taskCode, departmentCode); }
    @Override public List<TaskMaster> findAll() { return mapper.selectAll(); }
}
