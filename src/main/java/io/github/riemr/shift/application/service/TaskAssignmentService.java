package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.dto.TaskAssignmentResponse;
import io.github.riemr.shift.application.repository.TaskAssignmentRepository;
import io.github.riemr.shift.application.repository.TaskRepository;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Task;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskAssignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class TaskAssignmentService {
    private final TaskAssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;
    private final ShiftAssignmentMapper registerShiftMapper;

    public TaskAssignmentService(TaskAssignmentRepository assignmentRepository,
                                 TaskRepository taskRepository,
                                 ShiftAssignmentMapper registerShiftMapper) {
        this.assignmentRepository = assignmentRepository;
        this.taskRepository = taskRepository;
        this.registerShiftMapper = registerShiftMapper;
    }

    @Transactional
    public Long assignManually(Long taskId, String employeeCode, LocalDateTime startAt, LocalDateTime endAt, String createdBy) {
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("startAt < endAt required");
        }

        Task task = taskRepository.findById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }

        // Validate against task definition
        if (Objects.equals(task.getScheduleType(), "FIXED")) {
            if (!toLocalDateTime(task.getFixedStartAt()).equals(startAt) ||
                !toLocalDateTime(task.getFixedEndAt()).equals(endAt)) {
                throw new IllegalArgumentException("Fixed task must match the fixed start/end");
            }
        } else {
            LocalDateTime ws = toLocalDateTime(task.getWindowStartAt());
            LocalDateTime we = toLocalDateTime(task.getWindowEndAt());
            int req = task.getRequiredDurationMinutes() == null ? 0 : task.getRequiredDurationMinutes();
            if (ws == null || we == null || req <= 0) {
                throw new IllegalStateException("Task flexible window is not properly defined");
            }
            if (startAt.isBefore(ws) || endAt.isAfter(we)) {
                throw new IllegalArgumentException("Assignment must be inside the task window");
            }
            long minutes = java.time.Duration.between(startAt, endAt).toMinutes();
            if (minutes != req) {
                throw new IllegalArgumentException("Assignment duration must equal requiredDurationMinutes");
            }
        }

        // Overlap checks: Non-register self + Register shifts
        Date s = toDate(startAt);
        Date e = toDate(endAt);
        long nrOverlaps = assignmentRepository.countOverlaps(employeeCode, s, e);
        long regOverlaps = registerShiftMapper.countOverlaps(employeeCode, s, e);
        if (nrOverlaps > 0 || regOverlaps > 0) {
            throw new IllegalStateException("Assignment overlaps existing shifts");
        }

        TaskAssignment row = new TaskAssignment();
        row.setTaskId(taskId);
        row.setEmployeeCode(employeeCode);
        row.setStartAt(s);
        row.setEndAt(e);
        row.setSource("MANUAL");
        row.setStatus("PLANNED");
        row.setCreatedBy(createdBy);
        row.setCreatedAt(new Date());
        assignmentRepository.save(row);
        return row.getAssignmentId();
    }

    public List<TaskAssignmentResponse> listByTask(Long taskId) {
        return assignmentRepository.listByTaskId(taskId).stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<TaskAssignmentResponse> listByEmployeeAndDate(String employeeCode, LocalDate date) {
        Date from = toDate(date.atStartOfDay());
        Date to = toDate(date.atTime(LocalTime.MAX));
        return assignmentRepository.listByEmployeeAndDate(employeeCode, from, to).stream().map(this::toResponse).collect(Collectors.toList());
    }

    private TaskAssignmentResponse toResponse(TaskAssignment a) {
        return TaskAssignmentResponse.builder()
                .assignmentId(a.getAssignmentId())
                .taskId(a.getTaskId())
                .employeeCode(a.getEmployeeCode())
                .startAt(toLocalDateTime(a.getStartAt()))
                .endAt(toLocalDateTime(a.getEndAt()))
                .source(a.getSource())
                .status(a.getStatus())
                .build();
    }

    private static Date toDate(LocalDateTime ldt) {
        return ldt == null ? null : Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
