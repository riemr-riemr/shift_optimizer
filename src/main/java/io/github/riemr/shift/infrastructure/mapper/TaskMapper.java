package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface TaskMapper {
    Task selectByPrimaryKey(@Param("taskId") Long taskId);

    int insert(Task row);

    int updateByPrimaryKey(Task row);

    int deleteByPrimaryKey(@Param("taskId") Long taskId);

    List<Task> selectByStoreAndDate(@Param("storeCode") String storeCode,
                                    @Param("workDate") Date workDate);

    int deleteByStoreAndDateRange(@Param("storeCode") String storeCode,
                                  @Param("from") Date from,
                                  @Param("to") Date to);
}
