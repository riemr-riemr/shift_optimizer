package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.application.dto.DemandIntervalDto;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

public interface WorkDemandIntervalMapper {
    List<DemandIntervalDto> selectByDate(@Param("storeCode") String storeCode,
                                         @Param("departmentCode") String departmentCode,
                                         @Param("date") LocalDate date);

    List<DemandIntervalDto> selectByMonth(@Param("storeCode") String storeCode,
                                          @Param("departmentCode") String departmentCode,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    int insert(DemandIntervalDto dto);

    int deleteById(@Param("id") Long id);

    int deleteAll();

    int deleteByStoreDeptAndRange(@Param("storeCode") String storeCode,
                                  @Param("departmentCode") String departmentCode,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    int deleteByStoreAndRange(@Param("storeCode") String storeCode,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to);
}
