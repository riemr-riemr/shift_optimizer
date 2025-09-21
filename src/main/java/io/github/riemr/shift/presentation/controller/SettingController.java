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
        int startDay = appSettingService.getShiftCycleStartDay();
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDate cycleStart = computeCycleStart(now, startDay);
        java.time.LocalDate cycleEnd = cycleStart.plusMonths(1);
        model.addAttribute("startDay", startDay);
        model.addAttribute("cycleStart", cycleStart);
        model.addAttribute("cycleEnd", cycleEnd);
        return "settings/index";
    }

    @PostMapping
    public String update(@RequestParam("startDay") int day, Model model) {
        appSettingService.updateShiftCycleStartDay(day);
        int startDay = appSettingService.getShiftCycleStartDay();
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDate cycleStart = computeCycleStart(now, startDay);
        java.time.LocalDate cycleEnd = cycleStart.plusMonths(1);
        model.addAttribute("startDay", startDay);
        model.addAttribute("cycleStart", cycleStart);
        model.addAttribute("cycleEnd", cycleEnd);
        model.addAttribute("success", "保存しました");
        return "settings/index";
    }

    private java.time.LocalDate computeCycleStart(java.time.LocalDate anyDate, int startDay) {
        int dom = anyDate.getDayOfMonth();
        if (dom >= startDay) {
            int fixedDay = Math.min(startDay, anyDate.lengthOfMonth());
            return anyDate.withDayOfMonth(fixedDay);
        } else {
            java.time.LocalDate prev = anyDate.minusMonths(1);
            int fixedDay = Math.min(startDay, prev.lengthOfMonth());
            return prev.withDayOfMonth(fixedDay);
        }
    }
}
