package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskAssignment;

import java.util.Date;
import java.util.List;

public interface TaskAssignmentRepository {
    TaskAssignment findById(Long id);

    void save(TaskAssignment assignment);

    void update(TaskAssignment assignment);

    void delete(Long id);

    List<TaskAssignment> listByTaskId(Long taskId);

    List<TaskAssignment> listByEmployeeAndDate(String employeeCode, Date from, Date to);

    long countOverlaps(String employeeCode, Date startAt, Date endAt);
}
