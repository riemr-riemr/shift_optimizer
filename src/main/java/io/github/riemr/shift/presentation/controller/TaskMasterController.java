package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.TaskMasterService;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/tasks/master")
public class TaskMasterController {
    private final TaskMasterService service;
    public TaskMasterController(TaskMasterService service) { this.service = service; }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("list", service.list());
        model.addAttribute("form", new TaskMaster());
        return "tasks/master/list";
    }

    @PostMapping
    public String create(@ModelAttribute("form") TaskMaster form) {
        service.create(form);
        return "redirect:/tasks/master";
    }

    @PostMapping("/{taskCode}/delete")
    public String delete(@PathVariable("taskCode") @NotBlank String taskCode) {
        service.delete(taskCode);
        return "redirect:/tasks/master";
    }
}

