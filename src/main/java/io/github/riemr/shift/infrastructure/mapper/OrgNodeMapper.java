package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.OrgNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrgNodeMapper {
    List<OrgNode> selectAll();
    OrgNode selectById(@Param("nodeId") Long nodeId);
    OrgNode selectByCode(@Param("nodeCode") String nodeCode);
    List<OrgNode> selectChildren(@Param("parentNodeId") Long parentNodeId);
    int insert(OrgNode row);
    int update(OrgNode row);
    int deleteById(@Param("nodeId") Long nodeId);
}
