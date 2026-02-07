package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DepartmentMasterMapper {
    List<DepartmentMaster> selectAll();
    DepartmentMaster selectByCode(@Param("departmentCode") String departmentCode);
    int insert(DepartmentMaster row);
    int deleteByCode(@Param("departmentCode") String departmentCode);
}
