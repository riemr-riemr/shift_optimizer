package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.TaskSkillMatrixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequestMapping("/skills/tasks")
@RequiredArgsConstructor
public class TaskSkillMatrixController {
    private final TaskSkillMatrixService service;

    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).DEPT_SKILL_MATRIX)")
    public String view(Model model) {
        model.addAttribute("employees", service.listEmployees());
        model.addAttribute("tasks", service.listTasks());
        model.addAttribute("matrix", service.loadMatrix());
        return "skill/task-matrix";
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).DEPT_SKILL_MATRIX)")
    public String save(@RequestParam Map<String, String> params) {
        Map<String, Map<String, Short>> matrix = new HashMap<>();
        // params names: skill_empcode_taskcode
        for (var e : params.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("skill_")) continue;
            String[] parts = key.split("_", 3);
            if (parts.length < 3) continue;
            String rest = parts[2];
            int sep = rest.lastIndexOf('_');
            if (sep <= 0) continue;
            String emp = rest.substring(0, sep);
            String task = rest.substring(sep + 1);
            try {
                Short lvl = Short.parseShort(e.getValue());
                matrix.computeIfAbsent(emp, k -> new HashMap<>()).put(task, lvl);
            } catch (NumberFormatException ignore) {}
        }
        service.save(matrix);
        return "redirect:/skills/tasks";
    }
}

