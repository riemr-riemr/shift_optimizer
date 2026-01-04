package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentTaskAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DepartmentTaskAssignmentMapper {
    int insert(DepartmentTaskAssignment row);

    int deleteByMonthStoreAndDepartment(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("storeCode") String storeCode,
                                        @Param("departmentCode") String departmentCode);

    int deleteBreakByEmployeeAndDateRange(@Param("storeCode") String storeCode,
                                          @Param("departmentCode") String departmentCode,
                                          @Param("employeeCode") String employeeCode,
                                          @Param("from") java.util.Date from,
                                          @Param("to") java.util.Date to,
                                          @Param("taskCode") String taskCode,
                                          @Param("createdBy") String createdBy);

    List<DepartmentTaskAssignment> selectByMonth(@Param("from") LocalDate from,
                                                 @Param("to") LocalDate to,
                                                 @Param("storeCode") String storeCode,
                                                 @Param("departmentCode") String departmentCode);

    List<DepartmentTaskAssignment> selectByDate(@Param("storeCode") String storeCode,
                                                @Param("departmentCode") String departmentCode,
                                                @Param("from") java.time.LocalDate from,
                                                @Param("to") java.time.LocalDate to);
}
