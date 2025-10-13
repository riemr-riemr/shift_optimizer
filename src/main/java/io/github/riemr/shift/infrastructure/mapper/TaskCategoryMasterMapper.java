package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskCategoryMaster;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TaskCategoryMasterMapper {
    @Insert("INSERT INTO task_category_master (category_code, category_name, display_order, color, icon, active) " +
            "VALUES (#{categoryCode}, #{categoryName}, #{displayOrder}, #{color}, #{icon}, COALESCE(#{active}, true)) " +
            "ON CONFLICT (category_code) DO UPDATE SET " +
            "category_name = EXCLUDED.category_name, display_order = EXCLUDED.display_order, " +
            "color = EXCLUDED.color, icon = EXCLUDED.icon, active = EXCLUDED.active")
    int upsert(TaskCategoryMaster row);
}

