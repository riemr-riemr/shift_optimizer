package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.SkillMatrixService;
import io.github.riemr.shift.presentation.form.SkillMatrixForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/skills")
@RequiredArgsConstructor
public class SkillMatrixController {
    private final SkillMatrixService service;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SKILL_MATRIX)")
    public String show(Model model) {
        var dto = service.loadMatrix();
        model.addAttribute("matrixDto", dto);
        model.addAttribute("skillForm", new SkillMatrixForm());
        return "skill/matrix";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SKILL_MATRIX)")
    public String save(@Valid SkillMatrixForm skillForm, BindingResult result) {
        if (result.hasErrors()) {
            return "skill/matrix";
        }
        service.saveMatrix(skillForm.toEntityList());
        return "redirect:/skills";
    }
}
