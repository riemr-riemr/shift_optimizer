package io.github.riemr.shift.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.riemr.shift.domain.model.Register;
import io.github.riemr.shift.domain.model.RegisterId;

@Repository
public interface RegisterRepository extends JpaRepository<Register, RegisterId> {
    List<Register> findByStoreCode(String storeCode);
}