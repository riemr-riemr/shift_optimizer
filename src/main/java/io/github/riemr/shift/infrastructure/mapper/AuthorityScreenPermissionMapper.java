package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityScreenPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuthorityScreenPermissionMapper {
    AuthorityScreenPermission find(@Param("authorityCode") String authorityCode,
                                   @Param("screenCode") String screenCode);

    List<AuthorityScreenPermission> findAllByAuthority(@Param("authorityCode") String authorityCode);

    int upsert(AuthorityScreenPermission permission);
}
