package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartmentSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EmployeeDepartmentSkillMapper {
    List<EmployeeDepartmentSkill> selectByDepartment(@Param("departmentCode") String departmentCode);
    EmployeeDepartmentSkill find(@Param("employeeCode") String employeeCode,
                                 @Param("departmentCode") String departmentCode);
    int insert(EmployeeDepartmentSkill row);
}
