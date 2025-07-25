package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.domain.Register;
import io.github.riemr.shift.domain.RegisterKey;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RegisterMapper {

    int deleteByPrimaryKey(RegisterKey key);

    int insert(Register row);

    int insertSelective(Register row);

    Register selectByPrimaryKey(RegisterKey key);

    int updateByPrimaryKeySelective(Register row);

    int updateByPrimaryKey(Register row);

    List<Register> selectAll();
}