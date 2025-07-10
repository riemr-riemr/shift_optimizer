package io.github.riemr.shift.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.riemr.shift.domain.model.EmployeeRequest;

@Repository
public interface EmployeeRequestRepository extends JpaRepository<EmployeeRequest, Long> {
    List<EmployeeRequest> findByStoreCodeAndRequestDateBetween(String storeCode, LocalDate from, LocalDate to);
}
