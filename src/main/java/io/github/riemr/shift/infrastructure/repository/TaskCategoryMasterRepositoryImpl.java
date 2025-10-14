package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.TaskCategoryMasterRepository;
import io.github.riemr.shift.infrastructure.mapper.TaskCategoryMasterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskCategoryMaster;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TaskCategoryMasterRepositoryImpl implements TaskCategoryMasterRepository {
    private final TaskCategoryMasterMapper mapper;

    public TaskCategoryMasterRepositoryImpl(TaskCategoryMasterMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<TaskCategoryMaster> findAll() {
        return mapper.selectAll();
    }

    @Override
    public Optional<TaskCategoryMaster> findById(String categoryCode) {
        return Optional.ofNullable(mapper.selectByPrimaryKey(categoryCode));
    }

    @Override
    public void save(TaskCategoryMaster category) {
        mapper.upsert(category);
    }

    @Override
    public void delete(String categoryCode) {
        mapper.deleteByPrimaryKey(categoryCode);
    }
}