package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeTaskSkillMapper;
import io.github.riemr.shift.infrastructure.mapper.TaskMasterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeTaskSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskSkillMatrixService {
    private static final Logger log = LoggerFactory.getLogger(TaskSkillMatrixService.class);
    private final EmployeeMapper employeeMapper;
    private final TaskMasterMapper taskMasterMapper;
    private final EmployeeTaskSkillMapper employeeTaskSkillMapper;

    public List<Employee> listEmployees() {
        return employeeMapper.selectAll().stream()
                .peek(e -> { if (e.getStoreCode() == null) e.setStoreCode(""); })
                .toList();
    }

    public List<TaskMaster> listTasks() {
        return taskMasterMapper.selectAll().stream()
                .peek(t -> {
                    if (t.getDepartmentCode() == null || t.getDepartmentCode().isBlank()) {
                        t.setDepartmentCode("520");
                    }
                    if (t.getName() == null || t.getName().isBlank()) {
                        t.setName(t.getTaskCode());
                    }
                })
                .toList();
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

    public record SaveResult(int saved, int skipped, List<String> skippedEntries) {}

    @Transactional
    public SaveResult save(Map<String, Map<String, Short>> matrix) {
        if (matrix == null) return new SaveResult(0,0,List.of());
        // 事前に有効な従業員コード・タスクコードの集合を作成
        Set<String> validEmployees = employeeMapper.selectAll().stream()
                .map(Employee::getEmployeeCode)
                .collect(Collectors.toSet());
        Set<String> validTasks = taskMasterMapper.selectAll().stream()
                .map(TaskMaster::getTaskCode)
                .collect(Collectors.toSet());

        List<String> skippedEntries = new ArrayList<>();
        int saved = 0, skipped = 0;
        for (var e : matrix.entrySet()) {
            String emp = e.getKey();
            for (var t : e.getValue().entrySet()) {
                String task = t.getKey();
                Short lvl = t.getValue();
                if (emp == null || emp.isBlank() || task == null || task.isBlank() || lvl == null) continue;
                // 従業員/タスクの存在チェック（存在しないものはスキップして警告）
                if (!validEmployees.contains(emp)) {
                    skippedEntries.add("emp=" + emp + ", task=" + task);
                    skipped++;
                    continue;
                }
                if (!validTasks.contains(task)) {
                    skippedEntries.add("emp=" + emp + ", task=" + task);
                    skipped++;
                    continue;
                }
                EmployeeTaskSkill row = new EmployeeTaskSkill();
                row.setEmployeeCode(emp);
                row.setTaskCode(task);
                row.setSkillLevel(lvl);
                employeeTaskSkillMapper.upsert(row);
                saved++;
            }
        }
        if (!skippedEntries.isEmpty()) {
            log.warn("Skipped {} employee_task_skill entries due to invalid employee/task codes: {}",
                    skippedEntries.size(), skippedEntries);
        }
        return new SaveResult(saved, skipped, skippedEntries);
    }
}
