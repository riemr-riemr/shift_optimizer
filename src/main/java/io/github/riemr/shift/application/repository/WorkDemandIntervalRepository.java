package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.application.dto.DemandIntervalDto;

import java.time.LocalDate;
import java.util.List;

public interface WorkDemandIntervalRepository {
    List<DemandIntervalDto> findByDate(String storeCode, String departmentCode, LocalDate date);
    List<DemandIntervalDto> findByMonth(String storeCode, String departmentCode, LocalDate fromInclusive, LocalDate toExclusive);
    void insert(DemandIntervalDto dto);
    void deleteById(Long id);
}

