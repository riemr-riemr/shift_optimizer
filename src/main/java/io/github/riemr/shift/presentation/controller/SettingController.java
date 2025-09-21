package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.AppSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingController {
    private final AppSettingService appSettingService;

    @GetMapping
    public String view(Model model) {
        model.addAttribute("startDay", appSettingService.getShiftCycleStartDay());
        return "settings/index";
    }

    @PostMapping
    public String update(@RequestParam("startDay") int day, Model model) {
        appSettingService.updateShiftCycleStartDay(day);
        model.addAttribute("startDay", appSettingService.getShiftCycleStartDay());
        model.addAttribute("success", "保存しました");
        return "settings/index";
    }
}
