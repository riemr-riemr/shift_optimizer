package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.dto.SkillMatrixDto;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkillExample;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeRegisterSkillMapper;
import io.github.riemr.shift.infrastructure.mapper.RegisterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SkillMatrixService {
    private final EmployeeRegisterSkillMapper skillMapper;
    private final EmployeeMapper employeeMapper;
    private final RegisterMapper registerMapper;

    /** マトリクス表示用データ取得 */
    public SkillMatrixDto loadMatrix() {
        var employees = employeeMapper.selectAll();  // 全件取得
        var registers  = registerMapper.selectAll(); // 全件取得
        var skills     = skillMapper.selectByExample(new EmployeeRegisterSkillExample()); // 条件なしで全件
        // employeeCode → { registerNo(Integer) → skillLevel }
        Map<String, Map<Integer, Short>> levelMap = skills.stream()
            .collect(Collectors.groupingBy(EmployeeRegisterSkill::getEmployeeCode,
                     Collectors.toMap(EmployeeRegisterSkill::getRegisterNo,
                                        EmployeeRegisterSkill::getSkillLevel)));
        return new SkillMatrixDto(employees, registers, levelMap);
    }

    /** 入力マトリクスを保存 */
    @Transactional
    public void saveMatrix(List<EmployeeRegisterSkill> newList) {
        skillMapper.deleteByExample(new EmployeeRegisterSkillExample()); // シンプルに全削除 → 一括 INSERT
        newList.forEach(skillMapper::insertSelective);
    }
}
