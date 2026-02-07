package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.DepartmentSkillMatrixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/skills/department")
@RequiredArgsConstructor
public class DepartmentSkillMatrixController {
    private final DepartmentSkillMatrixService service;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).DEPT_SKILL_MATRIX)")
    public String view(@RequestParam(value = "departmentCode", required = false) String departmentCode,
                       Model model) {
        model.addAttribute("departments", service.listDepartments());
        model.addAttribute("employees", service.listEmployees());
        model.addAttribute("selectedDepartment", departmentCode);
        model.addAttribute("skillMap", service.loadSkillMap(departmentCode));
        return "skill/department-matrix";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).DEPT_SKILL_MATRIX)")
    public String save(@RequestParam("departmentCode") String departmentCode,
                       @RequestParam Map<String, String> params) {
        Map<String, Short> map = new HashMap<>();
        for (var e : params.entrySet()) {
            if (e.getKey().startsWith("skill_")) {
                String employeeCode = e.getKey().substring("skill_".length());
                try {
                    Short lvl = Short.parseShort(e.getValue());
                    map.put(employeeCode, lvl);
                } catch (NumberFormatException ignore) {}
            }
        }
        service.save(departmentCode, map);
        return "redirect:/skills/department?departmentCode=" + departmentCode;
    }
}

