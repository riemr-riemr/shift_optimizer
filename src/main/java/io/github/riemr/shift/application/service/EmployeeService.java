package io.github.riemr.shift.application.service;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeExample;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.persistence.entity.StoreExample;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeWeeklyPreferenceMapper;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeeService {
    private final EmployeeMapper mapper;
    private final EmployeeWeeklyPreferenceMapper weeklyMapper;
    private final StoreMapper storeMapper;
    private final io.github.riemr.shift.infrastructure.mapper.EmployeeMonthlyHoursSettingMapper monthlyHoursMapper;
    private final io.github.riemr.shift.infrastructure.mapper.EmployeeMonthlyOffdaysSettingMapper monthlyOffdaysMapper;

    public List<Employee> findAll() {
        EmployeeExample example = new EmployeeExample();
        example.setOrderByClause("CASE WHEN employee_code ~ '^[0-9]+$' THEN 0 ELSE 1 END, " +
                                 "CASE WHEN employee_code ~ '^[0-9]+$' THEN CAST(employee_code AS INTEGER) ELSE 0 END, " +
                                 "employee_code");
        return mapper.selectByExample(example);
    }

    public Employee find(String code) {
        return mapper.selectByPrimaryKey(code);
    }

    public java.util.List<EmployeeWeeklyPreference> findWeekly(String employeeCode) {
        return weeklyMapper.selectByEmployee(employeeCode);
    }

    public List<Store> findAllStores() {
        StoreExample example = new StoreExample();
        example.setOrderByClause("CAST(store_code AS INTEGER) ASC");
        return storeMapper.selectByExample(example);
    }

    @Transactional
    public void save(Employee e, boolean isNew, java.util.List<EmployeeWeeklyPreference> prefs,
                     java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyHoursSetting> monthlyHours,
                     java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyOffdaysSetting> monthlyOffdays) {
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
        // Replace monthly hours settings
        monthlyHoursMapper.deleteByEmployee(e.getEmployeeCode());
        if (monthlyHours != null) {
            for (var m : monthlyHours) {
                if (m.getMonthStart() != null) {
                    m.setEmployeeCode(e.getEmployeeCode());
                    monthlyHoursMapper.upsert(m);
                }
            }
        }

        // Replace monthly off-days settings
        monthlyOffdaysMapper.deleteByEmployee(e.getEmployeeCode());
        if (monthlyOffdays != null) {
            for (var m : monthlyOffdays) {
                if (m.getMonthStart() != null) {
                    m.setEmployeeCode(e.getEmployeeCode());
                    monthlyOffdaysMapper.upsert(m);
                }
            }
        }
    }

    @Transactional
    public void delete(String code) {
        mapper.deleteByPrimaryKey(code);
    }
}
