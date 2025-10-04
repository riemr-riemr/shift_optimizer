package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster;
import io.github.riemr.shift.infrastructure.persistence.entity.StoreDepartment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StoreDepartmentMapper {
    List<StoreDepartment> selectByStore(@Param("storeCode") String storeCode);
    List<DepartmentMaster> findDepartmentsByStore(@Param("storeCode") String storeCode);
    int insert(StoreDepartment row);
}
