package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EmployeeShiftPatternMapper {
    List<EmployeeShiftPattern> selectByEmployee(@Param("employeeCode") String employeeCode);
    List<EmployeeShiftPattern> selectAllActive();
    int upsert(EmployeeShiftPattern row);
    int delete(@Param("employeeCode") String employeeCode, @Param("patternCode") String patternCode);
}
