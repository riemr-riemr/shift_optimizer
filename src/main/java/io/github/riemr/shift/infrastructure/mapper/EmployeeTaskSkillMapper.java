package io.github.riemr.shift.infrastructure.mapper;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeTaskSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EmployeeTaskSkillMapper {
    List<EmployeeTaskSkill> selectAll();
    List<EmployeeTaskSkill> selectByTask(@Param("taskCode") String taskCode);
    int upsert(EmployeeTaskSkill row);
}

