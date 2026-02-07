package io.github.riemr.shift.application.repository;

import io.github.riemr.shift.application.dto.DemandIntervalDto;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public interface RegisterDemandIntervalRepository {
    List<DemandIntervalDto> findByStoreAndDate(String storeCode, LocalDate date);
    List<DemandIntervalDto> findByStoreAndMonth(String storeCode, YearMonth ym);
    void upsert(DemandIntervalDto dto);
    void deleteById(Long id);
}

