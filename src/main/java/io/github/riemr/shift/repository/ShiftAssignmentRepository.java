package io.github.riemr.shift.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.riemr.shift.domain.model.ShiftAssignment;

@Repository
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, Long> {
    List<ShiftAssignment> findByStoreCodeAndStartAtBetween(String storeCode, LocalDateTime from, LocalDateTime to);
}
