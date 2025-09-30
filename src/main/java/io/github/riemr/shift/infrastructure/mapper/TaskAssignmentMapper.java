package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface TaskAssignmentMapper {
    TaskAssignment selectByPrimaryKey(@Param("assignmentId") Long assignmentId);

    int insert(TaskAssignment row);

    int updateByPrimaryKey(TaskAssignment row);

    int deleteByPrimaryKey(@Param("assignmentId") Long assignmentId);

    List<TaskAssignment> selectByTaskId(@Param("taskId") Long taskId);

    List<TaskAssignment> selectByEmployeeAndDate(@Param("employeeCode") String employeeCode,
                                                      @Param("from") Date from,
                                                      @Param("to") Date to);

    Long countOverlaps(@Param("employeeCode") String employeeCode,
                       @Param("startAt") Date startAt,
                       @Param("endAt") Date endAt);
}
