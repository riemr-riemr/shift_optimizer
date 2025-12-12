package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.DaysMaster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DaysMasterMapper {
    int insert(DaysMaster row);
    int deleteByPrimaryKey(@Param("daysId") Long daysId);
    List<DaysMaster> selectSpecialByStore(@Param("storeCode") String storeCode);
}

