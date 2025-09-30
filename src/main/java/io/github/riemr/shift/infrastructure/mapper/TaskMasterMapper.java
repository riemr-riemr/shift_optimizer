package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskMasterMapper {
    int insert(TaskMaster row);
    int updateByPrimaryKey(TaskMaster row);
    int deleteByPrimaryKey(@Param("taskCode") String taskCode);
    TaskMaster selectByPrimaryKey(@Param("taskCode") String taskCode);
    List<TaskMaster> selectAll();
}

