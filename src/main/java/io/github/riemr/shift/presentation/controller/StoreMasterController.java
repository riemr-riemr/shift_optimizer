package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.persistence.entity.StoreExample;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@Controller
@RequestMapping("/masters/store")
@RequiredArgsConstructor
public class StoreMasterController {
    private final StoreMapper storeMapper;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String list(Model model) {
        model.addAttribute("form", new Store());
        model.addAttribute("list", storeMapper.selectByExample(new StoreExample()));
        return "masters/store";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String create(@ModelAttribute("form") Store form) {
        if (form.getStoreCode() != null && !form.getStoreCode().isBlank()) {
            // upsert if available, else insertSelective
            try { storeMapper.upsert(form); }
            catch (Exception e) { storeMapper.insertSelective(form); }
        }
        return "redirect:/masters/store";
    }

    @PostMapping("/{storeCode}/delete")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).TASK_MASTER)")
    public String delete(@PathVariable("storeCode") @NotBlank String storeCode) {
        storeMapper.deleteByPrimaryKey(storeCode);
        return "redirect:/masters/store";
    }
}

