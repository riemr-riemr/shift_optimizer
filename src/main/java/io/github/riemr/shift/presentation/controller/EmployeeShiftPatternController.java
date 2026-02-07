package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeShiftPatternMapper;
import io.github.riemr.shift.infrastructure.mapper.ShiftPatternMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeWeeklyPreferenceMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.*;

@Controller
@RequestMapping("/settings/employee-shift-matrix")
@RequiredArgsConstructor
public class EmployeeShiftPatternController {
    private final EmployeeMapper employeeMapper;
    private final ShiftPatternMapper shiftPatternMapper;
    private final EmployeeShiftPatternMapper employeeShiftPatternMapper;
    private final EmployeeWeeklyPreferenceMapper employeeWeeklyPreferenceMapper;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String matrix(Model model) {
        var employees = employeeMapper.selectAll();
        var patterns = shiftPatternMapper.selectAll();
        var links = employeeShiftPatternMapper.selectAllActive();
        Map<String, Map<String, EmployeeShiftPattern>> map = new HashMap<>();
        for (var l : links) {
            map.computeIfAbsent(l.getEmployeeCode(), k -> new HashMap<>()).put(l.getPatternCode(), l);
        }
        Map<String, Set<String>> outOfRangeMap = buildOutOfRangeMap(employees, patterns);
        model.addAttribute("employees", employees);
        model.addAttribute("patterns", patterns);
        model.addAttribute("linkMap", map);
        model.addAttribute("outOfRangeMap", outOfRangeMap);
        return "settings/employee_shift_matrix";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String update(@RequestParam Map<String,String> form) {
        // 一括保存: p_{employeeCode}_{patternCode} -> priority(0..4)
        for (var entry : form.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("p_")) continue;
            String val = entry.getValue();
            if (val == null || val.isBlank()) continue;
            String[] parts = key.substring(2).split("_", 2);
            if (parts.length != 2) continue;
            String employeeCode = parts[0];
            String patternCode = parts[1];
            try {
                int p = Integer.parseInt(val);
                p = Math.max(0, Math.min(4, p));
                EmployeeShiftPattern row = new EmployeeShiftPattern();
                row.setEmployeeCode(employeeCode);
                row.setPatternCode(patternCode);
                row.setPriority((short)p);
                row.setActive(true);
                employeeShiftPatternMapper.upsert(row);
            } catch (NumberFormatException ignore) { /* skip invalid */ }
        }
        return "redirect:/settings/employee-shift-matrix";
    }

    private Map<String, Set<String>> buildOutOfRangeMap(
            List<io.github.riemr.shift.infrastructure.persistence.entity.Employee> employees,
            List<io.github.riemr.shift.infrastructure.persistence.entity.ShiftPattern> patterns) {
        Map<String, Set<String>> result = new HashMap<>();
        for (var emp : employees) {
            var prefs = employeeWeeklyPreferenceMapper.selectByEmployee(emp.getEmployeeCode());
            List<java.time.LocalTime[]> ranges = new ArrayList<>();
            for (var p : prefs) {
                if ("OFF".equalsIgnoreCase(p.getWorkStyle())) continue;
                if (p.getBaseStartTime() == null || p.getBaseEndTime() == null) continue;
                ranges.add(new java.time.LocalTime[]{
                        p.getBaseStartTime().toLocalTime(),
                        p.getBaseEndTime().toLocalTime()
                });
            }
            if (ranges.isEmpty()) continue;
            for (var pat : patterns) {
                if (pat.getStartTime() == null || pat.getEndTime() == null) continue;
                var ps = pat.getStartTime().toLocalTime();
                var pe = pat.getEndTime().toLocalTime();
                boolean fitsAny = false;
                for (var r : ranges) {
                    if (!ps.isBefore(r[0]) && !pe.isAfter(r[1])) {
                        fitsAny = true;
                        break;
                    }
                }
                if (!fitsAny) {
                    result.computeIfAbsent(emp.getEmployeeCode(), k -> new HashSet<>())
                            .add(pat.getPatternCode());
                }
            }
        }
        return result;
    }
}
