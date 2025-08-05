package io.github.riemr.shift.application.service;

import io.github.riemr.shift.domain.Employee;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeeService {
    private final EmployeeMapper mapper;

    public List<Employee> findAll() {
        return mapper.selectAll();
    }

    public Employee find(String code) {
        return mapper.selectByPrimaryKey(code);
    }

    @Transactional
    public void save(Employee e, boolean isNew) {
        if (isNew) {
            mapper.insertSelective(e);
        } else {
            mapper.updateByPrimaryKeySelective(e);
        }
    }

    @Transactional
    public void delete(String code) {
        mapper.deleteByPrimaryKey(code);
    }
}