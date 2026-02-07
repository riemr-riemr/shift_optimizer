package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.infrastructure.mapper.ShiftPatternMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.ShiftPattern;
import io.github.riemr.shift.application.service.AppSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.sql.Time;

@Controller
@RequestMapping("/settings/shift-patterns")
@RequiredArgsConstructor
public class ShiftPatternController {

    private final ShiftPatternMapper mapper;
    private final AppSettingService appSettingService;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String list(Model model) {
        model.addAttribute("patterns", mapper.selectAll());
        int minutes = appSettingService.getTimeResolutionMinutes();
        model.addAttribute("timeResolutionSeconds", minutes * 60);
        return "settings/shift_pattern_list";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String upsert(@RequestParam String patternCode,
                         @RequestParam String startTime,
                         @RequestParam String endTime,
                         @RequestParam(defaultValue="true") boolean active) {
        ShiftPattern sp = mapper.selectByCode(patternCode);
        ShiftPattern row = new ShiftPattern();
        row.setPatternCode(patternCode);
        row.setStartTime(Time.valueOf(startTime + ":00"));
        row.setEndTime(Time.valueOf(endTime + ":00"));
        row.setActive(active);
        if (sp == null) mapper.insert(row); else mapper.update(row);
        return "redirect:/settings/shift-patterns";
    }

    @PostMapping("/delete")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String delete(@RequestParam String patternCode) {
        mapper.delete(patternCode);
        return "redirect:/settings/shift-patterns";
    }
}
