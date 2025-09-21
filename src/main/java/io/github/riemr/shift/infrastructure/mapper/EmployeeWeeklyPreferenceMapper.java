package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EmployeeWeeklyPreferenceMapper {
    List<EmployeeWeeklyPreference> selectByEmployee(@Param("employeeCode") String employeeCode);
    int deleteByEmployee(@Param("employeeCode") String employeeCode);
    int insert(EmployeeWeeklyPreference row);
}

