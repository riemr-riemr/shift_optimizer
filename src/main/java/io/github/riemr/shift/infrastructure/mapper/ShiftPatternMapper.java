package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.ShiftPattern;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShiftPatternMapper {
    List<ShiftPattern> selectAll();
    ShiftPattern selectByCode(@Param("patternCode") String patternCode);
    int insert(ShiftPattern row);
    int update(ShiftPattern row);
    int delete(@Param("patternCode") String patternCode);
}

