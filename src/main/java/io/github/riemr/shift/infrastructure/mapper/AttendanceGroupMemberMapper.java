package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.AttendanceGroupMember;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AttendanceGroupMemberMapper {
    List<AttendanceGroupMember> selectByConstraintIds(@Param("constraintIds") List<Long> constraintIds);
    int insertAll(@Param("rows") List<AttendanceGroupMember> rows);
    int deleteByConstraintId(@Param("constraintId") Long constraintId);
}
