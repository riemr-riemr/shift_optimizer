package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskCategoryMaster;

import java.util.List;
import java.util.Optional;

public interface TaskCategoryMasterRepository {
    List<TaskCategoryMaster> findAll();
    Optional<TaskCategoryMaster> findById(String categoryCode);
    void save(TaskCategoryMaster category);
    void delete(String categoryCode);
}