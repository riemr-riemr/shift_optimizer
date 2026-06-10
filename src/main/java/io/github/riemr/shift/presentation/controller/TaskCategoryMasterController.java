package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.TaskCategoryMasterService;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskCategoryMaster;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Collections;

@Controller
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/tasks/categories")
public class TaskCategoryMasterController {
    private final TaskCategoryMasterService service;

    @GetMapping
    public String list(Model model) {
        try {
            List<TaskCategoryMaster> categories = service.list();
            log.debug("Retrieved {} task categories", categories.size());
            model.addAttribute("categories", categories);
            model.addAttribute("form", new TaskCategoryMaster());
            return "tasks/categories/list";
        } catch (Exception e) {
            log.error("Failed to load task categories", e);
            model.addAttribute("error", "データの取得に失敗しました。");
            model.addAttribute("categories", Collections.emptyList());
            model.addAttribute("form", new TaskCategoryMaster());
            return "tasks/categories/list";
        }
    }

    @PostMapping
    public String create(@ModelAttribute("form") TaskCategoryMaster form) {
        service.save(form);
        return "redirect:/tasks/categories";
    }

    @PostMapping("/{categoryCode}/update")
    public String update(@PathVariable("categoryCode") @NotBlank String categoryCode,
                        @ModelAttribute TaskCategoryMaster form) {
        form.setCategoryCode(categoryCode);
        service.save(form);
        return "redirect:/tasks/categories";
    }

    @PostMapping("/{categoryCode}/delete")
    public String delete(@PathVariable("categoryCode") @NotBlank String categoryCode) {
        service.delete(categoryCode);
        return "redirect:/tasks/categories";
    }
}