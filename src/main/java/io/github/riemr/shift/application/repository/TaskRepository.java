package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.infrastructure.persistence.entity.Task;

import java.util.Date;
import java.util.List;

public interface TaskRepository {
    Task findById(Long id);

    void save(Task task);

    void update(Task task);

    void delete(Long id);

    List<Task> listByStoreAndDate(String storeCode, Date workDate);

    void deleteByStoreAndDateRange(String storeCode, Date from, Date to);
}
