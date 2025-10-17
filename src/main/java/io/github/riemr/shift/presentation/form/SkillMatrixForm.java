package io.github.riemr.shift.presentation.form;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SkillMatrixForm {
    /** hidden input で rowKey-colKey=level の形で受け取る */
    private List<String> cellList = new ArrayList<>();

    /** フォームデータ → エンティティリスト */
    public List<EmployeeRegisterSkill> toEntityList() {
        List<EmployeeRegisterSkill> list = new ArrayList<>();
        for (String cell : cellList) {
            // 形式: EMP001|1|3 (employeeCode|registerNo|skillLevel)
            String[] parts = cell.split("\\|");
            if (parts.length == 3) {
                EmployeeRegisterSkill skill = new EmployeeRegisterSkill();
                skill.setEmployeeCode(parts[0]);
                skill.setRegisterNo(Integer.valueOf(parts[1]));
                skill.setSkillLevel(Short.valueOf(parts[2]));
                list.add(skill);
            }
        }
        return list;
    }
}