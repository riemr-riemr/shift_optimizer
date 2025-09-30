package io.github.riemr.shift.infrastructure.repository;

import io.github.riemr.shift.application.repository.DaysMasterRepository;
import io.github.riemr.shift.infrastructure.mapper.DaysMasterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.DaysMaster;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DaysMasterRepositoryImpl implements DaysMasterRepository {
    private final DaysMasterMapper mapper;
    public DaysMasterRepositoryImpl(DaysMasterMapper mapper) { this.mapper = mapper; }
    @Override public void save(DaysMaster d) { mapper.insert(d); }
    @Override public void delete(Long daysId) { mapper.deleteByPrimaryKey(daysId); }
    @Override public List<DaysMaster> listSpecialByStore(String storeCode) { return mapper.selectSpecialByStore(storeCode); }
}

