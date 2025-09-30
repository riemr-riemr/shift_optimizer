package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.TaskRepository;
import io.github.riemr.shift.infrastructure.mapper.TaskMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Task;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public class TaskRepositoryImpl implements TaskRepository {

    private final TaskMapper mapper;

    public TaskRepositoryImpl(TaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Task findById(Long id) {
        return mapper.selectByPrimaryKey(id);
    }

    @Override
    public void save(Task task) {
        mapper.insert(task);
    }

    @Override
    public void update(Task task) {
        mapper.updateByPrimaryKey(task);
    }

    @Override
    public void delete(Long id) {
        mapper.deleteByPrimaryKey(id);
    }

    @Override
    public List<Task> listByStoreAndDate(String storeCode, Date workDate) {
        return mapper.selectByStoreAndDate(storeCode, workDate);
    }

    @Override
    public void deleteByStoreAndDateRange(String storeCode, Date from, Date to) {
        mapper.deleteByStoreAndDateRange(storeCode, from, to);
    }
}
