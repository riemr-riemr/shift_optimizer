package io.github.riemr.shift.service;

import io.github.riemr.shift.domain.model.ShiftAssignment;
import io.github.riemr.shift.domain.model.ShiftSchedule;
import io.github.riemr.shift.repository.ShiftAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ShiftAssignmentSaver {

    private final ShiftAssignmentRepository assignmentRepository;

    public ShiftAssignmentSaver(ShiftAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional
    public void save(ShiftSchedule schedule) {
        List<ShiftAssignment> assignments = schedule.getShiftAssignmentList();

        // 従業員とレジが割り当てられているもののみ保存対象
        List<ShiftAssignment> assigned = assignments.stream()
            .filter(a -> a.getEmployee() != null && a.getRegister() != null)
            .toList();

        assignmentRepository.saveAll(assigned);
    }
}
