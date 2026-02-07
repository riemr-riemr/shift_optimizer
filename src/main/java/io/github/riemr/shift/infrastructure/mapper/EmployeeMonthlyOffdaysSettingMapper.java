package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyOffdaysSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface EmployeeMonthlyOffdaysSettingMapper {
    List<EmployeeMonthlyOffdaysSetting> selectByEmployee(@Param("employeeCode") String employeeCode);
    List<EmployeeMonthlyOffdaysSetting> selectByMonth(@Param("monthStart") Date monthStart);
    List<EmployeeMonthlyOffdaysSetting> selectByEmployeeAndMonth(@Param("employeeCode") String employeeCode,
                                                                 @Param("monthStart") Date monthStart);
    int upsert(EmployeeMonthlyOffdaysSetting row);
    int deleteByEmployee(@Param("employeeCode") String employeeCode);
}
