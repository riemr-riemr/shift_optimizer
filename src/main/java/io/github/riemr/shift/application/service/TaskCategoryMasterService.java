package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.repository.TaskCategoryMasterRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskCategoryMaster;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TaskCategoryMasterService {
    private final TaskCategoryMasterRepository repository;

    public TaskCategoryMasterService(TaskCategoryMasterRepository repository) {
        this.repository = repository;
    }

    public List<TaskCategoryMaster> list() {
        return repository.findAll();
    }

    public Optional<TaskCategoryMaster> findById(String categoryCode) {
        return repository.findById(categoryCode);
    }

    public void save(TaskCategoryMaster category) {
        // Set default values if not provided
        if (category.getActive() == null) {
            category.setActive(true);
        }
        repository.save(category);
    }

    public void delete(String categoryCode) {
        repository.delete(categoryCode);
    }
}