package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.dto.TaskCreateRequest;
import io.github.riemr.shift.application.dto.TaskResponse;
import io.github.riemr.shift.application.repository.TaskRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class TaskService {
    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public Long createFixedTask(TaskCreateRequest req, String createdBy) {
        validateFixed(req);
        Task entity = toEntity(req, createdBy);
        taskRepository.save(entity);
        return entity.getTaskId();
    }

    @Transactional
    public Long createFlexibleTask(TaskCreateRequest req, String createdBy) {
        validateFlexible(req);
        Task entity = toEntity(req, createdBy);
        taskRepository.save(entity);
        return entity.getTaskId();
    }

    public List<TaskResponse> list(String storeCode, LocalDate workDate) {
        List<Task> rows = taskRepository.listByStoreAndDate(storeCode, toDate(workDate.atStartOfDay()));
        return rows.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public void delete(Long taskId) {
        taskRepository.delete(taskId);
    }

    private void validateFixed(TaskCreateRequest req) {
        if (!Objects.equals(req.getScheduleType(), "FIXED")) {
            throw new IllegalArgumentException("scheduleType must be FIXED");
        }
        LocalDateTime s = req.getFixedStartAt();
        LocalDateTime e = req.getFixedEndAt();
        if (s == null || e == null || !e.isAfter(s)) {
            throw new IllegalArgumentException("fixedStartAt < fixedEndAt required");
        }
        if (req.getRequiredStaffCount() == null || req.getRequiredStaffCount() < 1) {
            throw new IllegalArgumentException("requiredStaffCount >= 1 required");
        }
    }

    private void validateFlexible(TaskCreateRequest req) {
        if (!Objects.equals(req.getScheduleType(), "FLEXIBLE")) {
            throw new IllegalArgumentException("scheduleType must be FLEXIBLE");
        }
        LocalDateTime ws = req.getWindowStartAt();
        LocalDateTime we = req.getWindowEndAt();
        Integer dur = req.getRequiredDurationMinutes();
        if (ws == null || we == null || dur == null || dur < 1) {
            throw new IllegalArgumentException("windowStartAt, windowEndAt and requiredDurationMinutes are required");
        }
        if (!we.isAfter(ws)) {
            throw new IllegalArgumentException("windowEndAt must be after windowStartAt");
        }
        if ((we.minusMinutes(dur)).isBefore(ws)) {
            throw new IllegalArgumentException("window must be wide enough to fit duration");
        }
    }

    private Task toEntity(TaskCreateRequest req, String createdBy) {
        Date now = new Date();
        Task e = new Task();
        e.setStoreCode(req.getStoreCode());
        e.setWorkDate(toDate(req.getWorkDate().atStartOfDay()));
        e.setName(req.getName());
        e.setDescription(req.getDescription());
        e.setScheduleType(req.getScheduleType());
        if (Objects.equals(req.getScheduleType(), "FIXED")) {
            e.setFixedStartAt(toDate(req.getFixedStartAt()));
            e.setFixedEndAt(toDate(req.getFixedEndAt()));
            e.setRequiredStaffCount(req.getRequiredStaffCount());
        } else {
            e.setWindowStartAt(toDate(req.getWindowStartAt()));
            e.setWindowEndAt(toDate(req.getWindowEndAt()));
            e.setRequiredDurationMinutes(req.getRequiredDurationMinutes());
            e.setMustBeContiguous((short) (Boolean.TRUE.equals(req.getMustBeContiguous()) ? 1 : 0));
        }
        e.setRequiredSkillCode(req.getRequiredSkillCode());
        e.setPriority(req.getPriority());
        e.setCreatedBy(createdBy);
        e.setCreatedAt(now);
        e.setUpdatedBy(createdBy);
        e.setUpdatedAt(now);
        return e;
    }

    private TaskResponse toResponse(Task e) {
        return TaskResponse.builder()
                .taskId(e.getTaskId())
                .storeCode(e.getStoreCode())
                .workDate(toLocalDate(e.getWorkDate()))
                .name(e.getName())
                .description(e.getDescription())
                .scheduleType(e.getScheduleType())
                .fixedStartAt(toLocalDateTime(e.getFixedStartAt()))
                .fixedEndAt(toLocalDateTime(e.getFixedEndAt()))
                .windowStartAt(toLocalDateTime(e.getWindowStartAt()))
                .windowEndAt(toLocalDateTime(e.getWindowEndAt()))
                .requiredDurationMinutes(e.getRequiredDurationMinutes())
                .requiredSkillCode(e.getRequiredSkillCode())
                .requiredStaffCount(e.getRequiredStaffCount())
                .priority(e.getPriority())
                .mustBeContiguous(e.getMustBeContiguous() != null && e.getMustBeContiguous() == 1)
                .build();
    }

    private static Date toDate(LocalDateTime ldt) {
        return ldt == null ? null : Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static Date toDate(LocalDateTime ldt, ZoneId zoneId) {
        return ldt == null ? null : Date.from(ldt.atZone(zoneId).toInstant());
    }

    private static Date toDate(LocalDate ld) {
        return ld == null ? null : Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
