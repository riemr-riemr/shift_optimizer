package io.github.riemr.shift.application.dto;

import io.github.riemr.shift.domain.Employee;
import io.github.riemr.shift.domain.Register;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 画面表示に必要なマトリクス情報をまとめた DTO。
 */
@AllArgsConstructor
@Getter
public class SkillMatrixDto {
    private List<Employee> employees;
    private List<Register> registers;
    /**
     * employeeCode → { registerNo(Integer) → skillLevel }
     */
    private Map<String, Map<Integer, Short>> levelMap;
}