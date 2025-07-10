package io.github.riemr.shift.repository;

import io.github.riemr.shift.domain.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, String> {
    List<Employee> findByStore_StoreCode(String storeCode);
}
