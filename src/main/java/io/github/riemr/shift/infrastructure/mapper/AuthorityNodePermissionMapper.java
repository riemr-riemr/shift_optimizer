package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityNodePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuthorityNodePermissionMapper {
    List<AuthorityNodePermission> findAllByAuthority(@Param("authorityCode") String authorityCode);
    int upsert(AuthorityNodePermission row);
    int deleteByAuthority(@Param("authorityCode") String authorityCode);
}
