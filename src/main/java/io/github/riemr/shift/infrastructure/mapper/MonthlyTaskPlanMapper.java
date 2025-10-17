package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.MonthlyTaskPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface MonthlyTaskPlanMapper {
    int insert(MonthlyTaskPlan row);
    int updateByPrimaryKey(MonthlyTaskPlan row);
    int deleteByPrimaryKey(@Param("planId") Long planId);
    MonthlyTaskPlan selectByPrimaryKey(@Param("planId") Long planId);

    // child table inserts
    int insertDom(@Param("planId") Long planId, @Param("dayOfMonth") short dayOfMonth);
    int insertWom(@Param("planId") Long planId, @Param("weekOfMonth") short weekOfMonth, @Param("dayOfWeek") short dayOfWeek);
    int deleteDomByPlan(@Param("planId") Long planId);
    int deleteWomByPlan(@Param("planId") Long planId);

    // Query effective monthly plans matching a specific date (by DOM or WOM rule)
    List<MonthlyTaskPlan> selectEffectiveByStoreAndDate(@Param("storeCode") String storeCode,
                                                        @Param("date") Date date);
}

