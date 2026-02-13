package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.StoreOrgNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StoreOrgNodeMapper {
    List<StoreOrgNode> selectAll();
    StoreOrgNode findByStoreCode(@Param("storeCode") String storeCode);
    List<String> findStoreCodesByNode(@Param("nodeId") Long nodeId);
    int upsert(StoreOrgNode row);
    int deleteByStoreCode(@Param("storeCode") String storeCode);
}
