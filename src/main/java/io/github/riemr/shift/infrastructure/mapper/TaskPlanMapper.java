package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface TaskPlanMapper {
    int insert(TaskPlan row);
    int updateByPrimaryKey(TaskPlan row);
    int deleteByPrimaryKey(@Param("planId") Long planId);
    TaskPlan selectByPrimaryKey(@Param("planId") Long planId);

    List<TaskPlan> selectWeeklyByStoreAndDow(@Param("storeCode") String storeCode,
                                             @Param("dayOfWeek") short dayOfWeek);

    List<TaskPlan> selectWeeklyByStoreAndDowAndDept(@Param("storeCode") String storeCode,
                                                    @Param("dayOfWeek") short dayOfWeek,
                                                    @Param("departmentCode") String departmentCode);

    List<TaskPlan> selectWeeklyEffective(@Param("storeCode") String storeCode,
                                         @Param("dayOfWeek") short dayOfWeek,
                                         @Param("date") Date date);

    List<TaskPlan> selectSpecialByStoreAndRange(@Param("storeCode") String storeCode,
                                                @Param("from") Date from,
                                                @Param("to") Date to);

    List<TaskPlan> selectSpecialByStoreAndDate(@Param("storeCode") String storeCode,
                                               @Param("specialDate") Date specialDate);

    List<TaskPlan> selectSpecialByStoreAndDateAndDept(@Param("storeCode") String storeCode,
                                                      @Param("specialDate") Date specialDate,
                                                      @Param("departmentCode") String departmentCode);

    List<Date> selectSpecialDatesByStore(@Param("storeCode") String storeCode);
}
