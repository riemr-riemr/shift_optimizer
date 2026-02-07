package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.Register;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterKey;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RegisterMapper {

    int deleteByPrimaryKey(RegisterKey key);
    int deleteAll();

    int insert(Register row);

    int insertSelective(Register row);

    Register selectByPrimaryKey(RegisterKey key);

    int updateByPrimaryKeySelective(Register row);

    int updateByPrimaryKey(Register row);

    List<Register> selectAll();
    List<Register> selectByStoreCode(@Param("storeCode") String storeCode);

    // custom upsert
    int upsert(Register row);
}
