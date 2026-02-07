package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.TaskCategoryMaster;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TaskCategoryMasterMapper {
    @Insert("INSERT INTO task_category_master (category_code, category_name, display_order, color, icon, active) " +
            "VALUES (#{categoryCode}, #{categoryName}, #{displayOrder}, #{color}, #{icon}, COALESCE(#{active}, true)) " +
            "ON CONFLICT (category_code) DO UPDATE SET " +
            "category_name = EXCLUDED.category_name, display_order = EXCLUDED.display_order, " +
            "color = EXCLUDED.color, icon = EXCLUDED.icon, active = EXCLUDED.active")
    int upsert(TaskCategoryMaster row);

    @Select("SELECT * FROM task_category_master ORDER BY display_order, category_code")
    @Results({
        @Result(property = "categoryCode", column = "category_code"),
        @Result(property = "categoryName", column = "category_name"),
        @Result(property = "displayOrder", column = "display_order"),
        @Result(property = "color", column = "color"),
        @Result(property = "icon", column = "icon"),
        @Result(property = "active", column = "active")
    })
    List<TaskCategoryMaster> selectAll();

    @Select("SELECT * FROM task_category_master WHERE category_code = #{categoryCode}")
    @Results({
        @Result(property = "categoryCode", column = "category_code"),
        @Result(property = "categoryName", column = "category_name"),
        @Result(property = "displayOrder", column = "display_order"),
        @Result(property = "color", column = "color"),
        @Result(property = "icon", column = "icon"),
        @Result(property = "active", column = "active")
    })
    TaskCategoryMaster selectByPrimaryKey(String categoryCode);

    @Delete("DELETE FROM task_category_master WHERE category_code = #{categoryCode}")
    int deleteByPrimaryKey(String categoryCode);
}

