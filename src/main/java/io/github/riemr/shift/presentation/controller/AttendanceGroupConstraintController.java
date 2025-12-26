package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.AttendanceGroupConstraintService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/settings/attendance-groups")
@RequiredArgsConstructor
public class AttendanceGroupConstraintController {
    private final AttendanceGroupConstraintService service;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String view(@RequestParam(value = "storeCode", required = false) String storeCode,
                       @RequestParam(value = "departmentCode", required = false) String departmentCode,
                       Model model) {
        var stores = service.listStores();
        if ((storeCode == null || storeCode.isBlank()) && !stores.isEmpty()) {
            storeCode = stores.get(0).getStoreCode();
        }
        var departments = service.listDepartments();
        var employees = service.listEmployees();
        var constraints = service.listConstraints(storeCode, departmentCode);

        model.addAttribute("stores", stores);
        model.addAttribute("departments", departments);
        model.addAttribute("employees", employees);
        model.addAttribute("constraints", constraints);
        model.addAttribute("storeCode", storeCode);
        model.addAttribute("departmentCode", departmentCode);
        return "settings/attendance_group_constraints";
    }

    @PostMapping
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String create(@RequestParam("storeCode") String storeCode,
                         @RequestParam(value = "departmentCode", required = false) String departmentCode,
                         @RequestParam("ruleType") String ruleType,
                         @RequestParam(value = "minOnDuty", required = false) Integer minOnDuty,
                         @RequestParam(value = "memberEmployeeCodes", required = false) List<String> memberEmployeeCodes,
                         Model model) {
        try {
            service.createConstraint(storeCode, departmentCode, ruleType, minOnDuty, memberEmployeeCodes);
            model.addAttribute("success", "登録しました");
        } catch (RuntimeException ex) {
            model.addAttribute("error", ex.getMessage());
        }
        return view(storeCode, departmentCode, model);
    }

    @PostMapping("/delete")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SETTINGS)")
    public String delete(@RequestParam("constraintId") Long constraintId,
                         @RequestParam("storeCode") String storeCode,
                         @RequestParam(value = "departmentCode", required = false) String departmentCode,
                         Model model) {
        service.deleteConstraint(constraintId);
        model.addAttribute("success", "削除しました");
        return view(storeCode, departmentCode, model);
    }
}
