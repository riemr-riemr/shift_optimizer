package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EmployeeDepartmentMapper {
    List<EmployeeDepartment> selectByEmployee(@Param("employeeCode") String employeeCode);
    List<EmployeeDepartment> selectByDepartment(@Param("departmentCode") String departmentCode);
    int insert(EmployeeDepartment row);
}
