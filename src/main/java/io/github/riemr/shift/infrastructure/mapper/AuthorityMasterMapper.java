package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityMaster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuthorityMasterMapper {
    List<AuthorityMaster> selectAll();
    AuthorityMaster findByCode(@Param("authorityCode") String authorityCode);
    int insert(AuthorityMaster authorityMaster);
    int update(AuthorityMaster authorityMaster);
}
