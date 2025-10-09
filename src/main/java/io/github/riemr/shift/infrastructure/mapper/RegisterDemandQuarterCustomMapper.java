package io.github.riemr.shift.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

@Mapper
public interface RegisterDemandQuarterCustomMapper {
    int upsertWithGroup(@Param("storeCode") String storeCode,
                        @Param("demandDate") Date demandDate,
                        @Param("slotTime") java.time.LocalTime slotTime,
                        @Param("requiredUnits") Integer requiredUnits,
                        @Param("groupId") String groupId);
}

