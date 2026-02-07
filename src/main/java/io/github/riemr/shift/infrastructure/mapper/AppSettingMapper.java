package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.AppSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AppSettingMapper {
    AppSetting selectByKey(@Param("key") String key);
    int upsert(@Param("key") String key, @Param("value") String value);
}

