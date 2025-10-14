package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.dto.TaskCreateRequest;
import io.github.riemr.shift.application.service.TaskAssignmentService;
import io.github.riemr.shift.application.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Controller
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskAssignmentService assignmentService;

    public TaskController(TaskService taskService,
                          TaskAssignmentService assignmentService) {
        this.taskService = taskService;
        this.assignmentService = assignmentService;
    }

    @GetMapping("/new")
    public String newTaskForm(Model model,
                              @RequestParam(name = "store", required = false) String storeCode,
                              @RequestParam(name = "date", required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        TaskCreateRequest form = new TaskCreateRequest();
        form.setScheduleType("FIXED");
        if (storeCode != null) form.setStoreCode(storeCode);
        if (date != null) form.setWorkDate(date);
        model.addAttribute("form", form);
        return "tasks/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") TaskCreateRequest form,
                         BindingResult bindingResult,
                         Model model) {
        if (bindingResult.hasErrors()) {
            return "tasks/form";
        }

        String createdBy = "system"; // TODO integrate with auth user
        if ("FIXED".equals(form.getScheduleType())) {
            taskService.createFixedTask(form, createdBy);
        } else {
            taskService.createFlexibleTask(form, createdBy);
        }
        // Redirect to task plan page after creation instead of list screen
        return "redirect:/tasks/plan?store=" + form.getStoreCode() + "&sd=" + form.getWorkDate();
    }

    @PostMapping("/{taskId}/assign")
    public String assign(@PathVariable("taskId") Long taskId,
                         @RequestParam("employeeCode") String employeeCode,
                         @RequestParam("startAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
                         @RequestParam("endAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
                         @RequestParam("store") String storeCode,
                         @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String createdBy = "system"; // TODO auth user
        assignmentService.assignManually(taskId, employeeCode, startAt, endAt, createdBy);
        // Redirect to task plan page after assignment instead of list screen
        return "redirect:/tasks/plan?store=" + storeCode + "&sd=" + date;
    }
}
