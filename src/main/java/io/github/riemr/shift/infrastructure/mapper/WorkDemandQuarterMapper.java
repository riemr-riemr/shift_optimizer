package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.WorkDemandQuarter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface WorkDemandQuarterMapper {
    List<WorkDemandQuarter> selectByDate(@Param("storeCode") String storeCode,
                                         @Param("departmentCode") String departmentCode,
                                         @Param("date") LocalDate date);

    List<WorkDemandQuarter> selectByMonth(@Param("storeCode") String storeCode,
                                          @Param("departmentCode") String departmentCode,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    int insert(WorkDemandQuarter row);
}
