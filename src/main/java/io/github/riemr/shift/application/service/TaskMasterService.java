package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.repository.TaskMasterRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TaskMasterService {
    private final TaskMasterRepository repository;

    public TaskMasterService(TaskMasterRepository repository) { this.repository = repository; }

    public List<TaskMaster> list() { return repository.findAll(); }

    public TaskMaster get(String taskCode) { return repository.find(taskCode); }

    @Transactional
    public void create(TaskMaster m) { repository.save(m); }

    @Transactional
    public void update(TaskMaster m) { repository.update(m); }

    @Transactional
    public void delete(String taskCode) { repository.delete(taskCode); }
}

