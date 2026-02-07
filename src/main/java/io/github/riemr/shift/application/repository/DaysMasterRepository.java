package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.infrastructure.persistence.entity.DaysMaster;
import java.util.List;

public interface DaysMasterRepository {
    void save(DaysMaster d);
    void delete(Long daysId);
    List<DaysMaster> listSpecialByStore(String storeCode);
}

