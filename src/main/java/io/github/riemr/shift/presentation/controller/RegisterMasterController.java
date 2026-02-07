package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.infrastructure.mapper.RegisterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Register;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterKey;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@Controller
@RequestMapping("/masters/register")
@RequiredArgsConstructor
public class RegisterMasterController {
    private final RegisterMapper registerMapper;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String list(Model model) {
        model.addAttribute("form", new Register());
        model.addAttribute("list", registerMapper.selectAll());
        return "masters/register";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String create(@ModelAttribute("form") Register form) {
        if (form.getStoreCode() != null && !form.getStoreCode().isBlank() && form.getRegisterNo() != null) {
            try { registerMapper.upsert(form); }
            catch (Exception e) { registerMapper.insertSelective(form); }
        }
        return "redirect:/masters/register";
    }

    @PostMapping("/{storeCode}/{registerNo}/delete")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String delete(@PathVariable("storeCode") @NotBlank String storeCode,
                         @PathVariable("registerNo") Integer registerNo) {
        RegisterKey key = new RegisterKey();
        key.setStoreCode(storeCode);
        key.setRegisterNo(registerNo);
        registerMapper.deleteByPrimaryKey(key);
        return "redirect:/masters/register";
    }
}

