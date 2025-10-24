package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.TaskSkillMatrixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequestMapping("/skills/tasks")
@RequiredArgsConstructor
public class TaskSkillMatrixController {
    private final TaskSkillMatrixService service;
    private final io.github.riemr.shift.infrastructure.mapper.StoreMapper storeMapper;
    private final io.github.riemr.shift.infrastructure.mapper.StoreDepartmentMapper storeDepartmentMapper;

    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).DEPT_SKILL_MATRIX)")
    public String view(Model model) {
        model.addAttribute("employees", service.listEmployees());
        model.addAttribute("tasks", service.listTasks());
        model.addAttribute("matrix", service.loadMatrix());
        // 既存のマスタから店舗・部門を提供
        var stores = storeMapper.selectByExample(new io.github.riemr.shift.infrastructure.persistence.entity.StoreExample());
        stores.sort(java.util.Comparator.comparing(io.github.riemr.shift.infrastructure.persistence.entity.Store::getStoreCode));
        model.addAttribute("stores", stores);
        java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster> depts = java.util.List.of();
        if (!stores.isEmpty()) {
            depts = storeDepartmentMapper.findDepartmentsByStore(stores.get(0).getStoreCode());
        }
        model.addAttribute("departments", depts);
        return "skill/task-matrix";
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).DEPT_SKILL_MATRIX)")
    public String save(@RequestParam Map<String, String> params) {
        Map<String, Map<String, Short>> matrix = new HashMap<>();
        // params names: skill_{empCode}_{taskCode}
        for (var e : params.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("skill_")) continue;
            String[] parts = key.split("_", 3);
            if (parts.length < 3) continue;
            String emp = parts[1];
            String task = parts[2];
            try {
                Short lvl = Short.parseShort(e.getValue());
                matrix.computeIfAbsent(emp, k -> new HashMap<>()).put(task, lvl);
            } catch (NumberFormatException ignore) {}
        }
        service.save(matrix);
        return "redirect:/skills/tasks";
    }

    @PostMapping(value = "/api", produces = "application/json")
    @ResponseBody
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).DEPT_SKILL_MATRIX)")
    public Map<String, Object> saveApi(@RequestParam Map<String, String> params) {
        Map<String, Map<String, Short>> matrix = new HashMap<>();
        for (var e : params.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("skill_")) continue;
            String[] parts = key.split("_", 3);
            if (parts.length < 3) continue;
            String emp = parts[1];
            String task = parts[2];
            try {
                Short lvl = Short.parseShort(e.getValue());
                matrix.computeIfAbsent(emp, k -> new HashMap<>()).put(task, lvl);
            } catch (NumberFormatException ignore) {}
        }
        var result = service.save(matrix);
        Map<String, Object> res = new HashMap<>();
        res.put("saved", result.saved());
        res.put("skipped", result.skipped());
        res.put("skippedEntries", result.skippedEntries());
        return res;
    }

    @GetMapping(value = "/api/departments", produces = "application/json")
    @ResponseBody
    public java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster> departmentsByStore(@RequestParam("storeCode") String storeCode) {
        if (storeCode == null || storeCode.isBlank()) return java.util.List.of();
        return storeDepartmentMapper.findDepartmentsByStore(storeCode);
    }
}
