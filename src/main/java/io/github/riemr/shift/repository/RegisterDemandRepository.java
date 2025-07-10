package io.github.riemr.shift.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.riemr.shift.domain.model.RegisterDemand;

@Repository
public interface RegisterDemandRepository extends JpaRepository<RegisterDemand, Long> {
    List<RegisterDemand> findByStoreCodeAndDateBetween(String storeCode, LocalDate from, LocalDate to);
}
