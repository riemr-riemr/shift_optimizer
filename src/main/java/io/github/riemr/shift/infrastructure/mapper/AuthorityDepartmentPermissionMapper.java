package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityDepartmentPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuthorityDepartmentPermissionMapper {
    List<AuthorityDepartmentPermission> findAllByAuthority(@Param("authorityCode") String authorityCode);
    List<String> findDepartmentCodesByAuthority(@Param("authorityCode") String authorityCode);
    int insert(AuthorityDepartmentPermission permission);
    int deleteByAuthority(@Param("authorityCode") String authorityCode);
}
