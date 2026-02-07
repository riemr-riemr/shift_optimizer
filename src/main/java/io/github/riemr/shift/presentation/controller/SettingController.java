package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.AppSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.time.LocalDate;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingController {
    private final AppSettingService appSettingService;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String view(Model model) {
        int startDay = appSettingService.getShiftCycleStartDay();
        int timeRes = appSettingService.getTimeResolutionMinutes();
        boolean timeResolutionLocked = appSettingService.isTimeResolutionChangeLocked();
        LocalDate now = LocalDate.now();
        LocalDate cycleStart = computeCycleStart(now, startDay);
        LocalDate cycleEnd = cycleStart.plusMonths(1);
        model.addAttribute("startDay", startDay);
        model.addAttribute("timeResolutionMinutes", timeRes);
        model.addAttribute("timeResolutionLocked", timeResolutionLocked);
        model.addAttribute("cycleStart", cycleStart);
        model.addAttribute("cycleEnd", cycleEnd);
        return "settings/index";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String update(@RequestParam("startDay") int day, Model model) {
        appSettingService.updateShiftCycleStartDay(day);
        int startDay = appSettingService.getShiftCycleStartDay();
        int timeRes = appSettingService.getTimeResolutionMinutes();
        boolean timeResolutionLocked = appSettingService.isTimeResolutionChangeLocked();
        LocalDate now = LocalDate.now();
        LocalDate cycleStart = computeCycleStart(now, startDay);
        LocalDate cycleEnd = cycleStart.plusMonths(1);
        model.addAttribute("startDay", startDay);
        model.addAttribute("timeResolutionMinutes", timeRes);
        model.addAttribute("timeResolutionLocked", timeResolutionLocked);
        model.addAttribute("cycleStart", cycleStart);
        model.addAttribute("cycleEnd", cycleEnd);
        model.addAttribute("success", "保存しました");
        return "settings/index";
    }

    @PostMapping("/time-resolution")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String updateTimeResolution(@RequestParam("timeResolutionMinutes") int minutes, Model model) {
        try {
            appSettingService.updateTimeResolutionMinutes(minutes);
            model.addAttribute("success", "シフト粒度を更新しました");
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        int startDay = appSettingService.getShiftCycleStartDay();
        int timeRes = appSettingService.getTimeResolutionMinutes();
        boolean timeResolutionLocked = appSettingService.isTimeResolutionChangeLocked();
        LocalDate now = LocalDate.now();
        LocalDate cycleStart = computeCycleStart(now, startDay);
        LocalDate cycleEnd = cycleStart.plusMonths(1);
        model.addAttribute("startDay", startDay);
        model.addAttribute("timeResolutionMinutes", timeRes);
        model.addAttribute("timeResolutionLocked", timeResolutionLocked);
        model.addAttribute("cycleStart", cycleStart);
        model.addAttribute("cycleEnd", cycleEnd);
        return "settings/index";
    }

    private LocalDate computeCycleStart(LocalDate anyDate, int startDay) {
        int dom = anyDate.getDayOfMonth();
        if (dom >= startDay) {
            int fixedDay = Math.min(startDay, anyDate.lengthOfMonth());
            return anyDate.withDayOfMonth(fixedDay);
        } else {
            LocalDate prev = anyDate.minusMonths(1);
            int fixedDay = Math.min(startDay, prev.lengthOfMonth());
            return prev.withDayOfMonth(fixedDay);
        }
    }
}
