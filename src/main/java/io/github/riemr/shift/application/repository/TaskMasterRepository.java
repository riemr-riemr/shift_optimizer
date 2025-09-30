package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import java.util.List;

public interface TaskMasterRepository {
    void save(TaskMaster m);
    void update(TaskMaster m);
    void delete(String taskCode);
    TaskMaster find(String taskCode);
    List<TaskMaster> findAll();
}

