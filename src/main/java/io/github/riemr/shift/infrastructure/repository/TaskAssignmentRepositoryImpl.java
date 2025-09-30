package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.TaskAssignmentRepository;
import io.github.riemr.shift.infrastructure.mapper.TaskAssignmentMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskAssignment;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public class TaskAssignmentRepositoryImpl implements TaskAssignmentRepository {

    private final TaskAssignmentMapper mapper;

    public TaskAssignmentRepositoryImpl(TaskAssignmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TaskAssignment findById(Long id) {
        return mapper.selectByPrimaryKey(id);
    }

    @Override
    public void save(TaskAssignment assignment) {
        mapper.insert(assignment);
    }

    @Override
    public void update(TaskAssignment assignment) {
        mapper.updateByPrimaryKey(assignment);
    }

    @Override
    public void delete(Long id) {
        mapper.deleteByPrimaryKey(id);
    }

    @Override
    public List<TaskAssignment> listByTaskId(Long taskId) {
        return mapper.selectByTaskId(taskId);
    }

    @Override
    public List<TaskAssignment> listByEmployeeAndDate(String employeeCode, Date from, Date to) {
        return mapper.selectByEmployeeAndDate(employeeCode, from, to);
    }

    @Override
    public long countOverlaps(String employeeCode, Date startAt, Date endAt) {
        Long v = mapper.countOverlaps(employeeCode, startAt, endAt);
        return v == null ? 0L : v;
    }
}
