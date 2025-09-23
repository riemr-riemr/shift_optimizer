package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.AuthorityMaster;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AuthorityMasterMapper {
    List<AuthorityMaster> selectAll();
}

