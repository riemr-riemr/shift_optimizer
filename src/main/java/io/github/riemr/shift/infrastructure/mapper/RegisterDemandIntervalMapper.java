package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.application.dto.DemandIntervalDto;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

public interface RegisterDemandIntervalMapper {
    List<DemandIntervalDto> selectByStoreAndDate(@Param("storeCode") String storeCode,
                                                 @Param("targetDate") LocalDate targetDate);

    List<DemandIntervalDto> selectByStoreAndMonth(@Param("storeCode") String storeCode,
                                                  @Param("targetMonth") String targetMonth);

    int upsert(DemandIntervalDto dto);

    int deleteById(@Param("id") Long id);

    int deleteByStoreAndDate(@Param("storeCode") String storeCode,
                             @Param("targetDate") LocalDate targetDate);

    int deleteAll();
}
