package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.infrastructure.mapper.DepartmentMasterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;;

@Controller
@RequestMapping("/masters/department")
@RequiredArgsConstructor
public class DepartmentMasterController {
    private final DepartmentMasterMapper departmentMasterMapper;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String list(Model model) {
        model.addAttribute("form", new DepartmentMaster());
        model.addAttribute("list", departmentMasterMapper.selectAll());
        return "masters/department";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String create(@ModelAttribute("form") DepartmentMaster form) {
        if (form.getDepartmentCode() != null && !form.getDepartmentCode().isBlank()) {
            try {
                departmentMasterMapper.insert(form);
            } catch (Exception ignore) {
                // ignore duplicates
            }
        }
        return "redirect:/masters/department";
    }

    @PostMapping("/{departmentCode}/delete")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String delete(@PathVariable("departmentCode") @NotBlank String departmentCode) {
        departmentMasterMapper.deleteByCode(departmentCode);
        return "redirect:/masters/department";
    }
}

