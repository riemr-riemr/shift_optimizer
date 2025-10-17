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

    int deleteWeeklyByStoreDeptAndDow(@Param("storeCode") String storeCode,
                                      @Param("departmentCode") String departmentCode,
                                      @Param("dayOfWeek") short dayOfWeek);

}
