package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyHoursSetting;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface EmployeeMonthlyHoursSettingMapper {
    List<EmployeeMonthlyHoursSetting> selectByEmployee(@Param("employeeCode") String employeeCode);
    List<EmployeeMonthlyHoursSetting> selectByMonth(@Param("monthStart") Date monthStart);
    int upsert(EmployeeMonthlyHoursSetting row);
    int deleteByEmployee(@Param("employeeCode") String employeeCode);
}

