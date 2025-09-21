package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeWeeklyPreferenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeeService {
    private final EmployeeMapper mapper;
    private final EmployeeWeeklyPreferenceMapper weeklyMapper;

    public List<Employee> findAll() {
        return mapper.selectAll();
    }

    public Employee find(String code) {
        return mapper.selectByPrimaryKey(code);
    }

    public java.util.List<EmployeeWeeklyPreference> findWeekly(String employeeCode) {
        return weeklyMapper.selectByEmployee(employeeCode);
    }

    @Transactional
    public void save(Employee e, boolean isNew, java.util.List<EmployeeWeeklyPreference> prefs) {
        if (isNew) {
            mapper.insertSelective(e);
        } else {
            mapper.updateByPrimaryKeySelective(e);
        }
        // Replace weekly preferences
        weeklyMapper.deleteByEmployee(e.getEmployeeCode());
        if (prefs != null) {
            for (var p : prefs) {
                p.setEmployeeCode(e.getEmployeeCode());
                weeklyMapper.insert(p);
            }
        }
    }

    @Transactional
    public void delete(String code) {
        mapper.deleteByPrimaryKey(code);
    }
}
