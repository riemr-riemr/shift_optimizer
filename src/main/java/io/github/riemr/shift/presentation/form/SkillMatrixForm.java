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
            // 形式: EMP001|REG01|3
            String[] parts = cell.split("\\|");
            if (parts.length == 3) {
                list.add(new EmployeeRegisterSkill(parts[0], parts[1], Integer.valueOf(parts[2])));
            }
        }
        return list;
    }
}