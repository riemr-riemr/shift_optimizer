package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlySetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface EmployeeMonthlySettingMapper {
    List<EmployeeMonthlySetting> selectByMonth(@Param("monthStart") Date monthStart);
    List<EmployeeMonthlySetting> selectByEmployeeAndMonth(@Param("employeeCode") String employeeCode,
                                                          @Param("monthStart") Date monthStart);
}

