package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.DepartmentMasterMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeDepartmentSkillMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeDepartmentSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DepartmentSkillMatrixService {
    private final EmployeeMapper employeeMapper;
    private final DepartmentMasterMapper departmentMasterMapper;
    private final EmployeeDepartmentSkillMapper employeeDepartmentSkillMapper;

    public List<DepartmentMaster> listDepartments() {
        return departmentMasterMapper.selectAll();
    }

    public List<Employee> listEmployees() {
        return employeeMapper.selectAll();
    }

    public Map<String, Short> loadSkillMap(String departmentCode) {
        if (departmentCode == null || departmentCode.isBlank()) return Map.of();
        return employeeDepartmentSkillMapper.selectByDepartment(departmentCode).stream()
                .collect(Collectors.toMap(EmployeeDepartmentSkill::getEmployeeCode, EmployeeDepartmentSkill::getSkillLevel));
    }

    @Transactional
    public void save(String departmentCode, Map<String, Short> skillMap) {
        if (departmentCode == null || departmentCode.isBlank() || skillMap == null) return;
        for (var entry : skillMap.entrySet()) {
            String emp = entry.getKey();
            Short level = entry.getValue();
            if (emp == null || emp.isBlank() || level == null) continue;
            var row = new EmployeeDepartmentSkill();
            row.setEmployeeCode(emp);
            row.setDepartmentCode(departmentCode);
            row.setSkillLevel(level);
            employeeDepartmentSkillMapper.insert(row); // Upsert via ON CONFLICT
        }
    }
}

