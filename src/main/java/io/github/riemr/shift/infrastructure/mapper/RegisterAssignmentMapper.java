package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.RegisterAssignment;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterAssignmentExample;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RegisterAssignmentMapper {
    long countByExample(RegisterAssignmentExample example);

    int deleteByExample(RegisterAssignmentExample example);

    int deleteByPrimaryKey(Long assignmentId);

    int insert(RegisterAssignment row);

    int insertSelective(RegisterAssignment row);

    List<RegisterAssignment> selectByExample(RegisterAssignmentExample example);

    RegisterAssignment selectByPrimaryKey(Long assignmentId);

    int updateByExampleSelective(@Param("row") RegisterAssignment row, @Param("example") RegisterAssignmentExample example);

    int updateByExample(@Param("row") RegisterAssignment row, @Param("example") RegisterAssignmentExample example);

    int updateByPrimaryKeySelective(RegisterAssignment row);

    int updateByPrimaryKey(RegisterAssignment row);

    // Custom methods
    int deleteByProblemId(@Param("problemId") Long problemId);

    List<RegisterAssignment> selectByMonth(@Param("from") LocalDate from, @Param("to") LocalDate to);

    List<RegisterAssignment> selectByDate(@Param("from") LocalDate from,
                                          @Param("to") LocalDate to);
    
    int deleteByEmployeeCodeAndTimeRange(@Param("employeeCode") String employeeCode, 
                                       @Param("startAt") Date startAt, 
                                       @Param("endAt") Date endAt);

    int deleteByEmployeeCodeStoreAndTimeRange(@Param("employeeCode") String employeeCode,
                                              @Param("storeCode") String storeCode,
                                              @Param("startAt") Date startAt,
                                              @Param("endAt") Date endAt);

    int deleteByMonthAndStore(@Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("storeCode") String storeCode);
}
