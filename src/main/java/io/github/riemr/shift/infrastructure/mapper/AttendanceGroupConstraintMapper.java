package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.AttendanceGroupConstraint;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AttendanceGroupConstraintMapper {
    List<AttendanceGroupConstraint> selectByStoreAndDepartment(@Param("storeCode") String storeCode,
                                                               @Param("departmentCode") String departmentCode);
    int insert(AttendanceGroupConstraint row);
    int deleteByPrimaryKey(@Param("constraintId") Long constraintId);
}
