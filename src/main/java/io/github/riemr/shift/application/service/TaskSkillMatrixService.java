package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeTaskSkillMapper;
import io.github.riemr.shift.infrastructure.mapper.TaskMasterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeTaskSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskSkillMatrixService {
    private final EmployeeMapper employeeMapper;
    private final TaskMasterMapper taskMasterMapper;
    private final EmployeeTaskSkillMapper employeeTaskSkillMapper;

    public List<Employee> listEmployees() {
        return employeeMapper.selectAll();
    }

    public List<TaskMaster> listTasks() {
        return taskMasterMapper.selectAll();
    }

    public Map<String, Map<String, Short>> loadMatrix() {
        // employee -> (task -> level)
        Map<String, Map<String, Short>> map = new HashMap<>();
        for (EmployeeTaskSkill s : employeeTaskSkillMapper.selectAll()) {
            map.computeIfAbsent(s.getEmployeeCode(), k -> new HashMap<>())
               .put(s.getTaskCode(), s.getSkillLevel());
        }
        return map;
    }

    @Transactional
    public void save(Map<String, Map<String, Short>> matrix) {
        if (matrix == null) return;
        for (var e : matrix.entrySet()) {
            String emp = e.getKey();
            for (var t : e.getValue().entrySet()) {
                String task = t.getKey();
                Short lvl = t.getValue();
                if (emp == null || emp.isBlank() || task == null || task.isBlank() || lvl == null) continue;
                EmployeeTaskSkill row = new EmployeeTaskSkill();
                row.setEmployeeCode(emp);
                row.setTaskCode(task);
                row.setSkillLevel(lvl);
                employeeTaskSkillMapper.upsert(row);
            }
        }
    }
}

