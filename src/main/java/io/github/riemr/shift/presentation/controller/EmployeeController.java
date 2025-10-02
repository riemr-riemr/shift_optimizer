package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.service.EmployeeService;
import io.github.riemr.shift.presentation.form.EmployeeForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/employees")
@RequiredArgsConstructor
public class EmployeeController {
    private final EmployeeService service;

    /* 一覧 */
    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_LIST)")
    public String list(Model model) {
        model.addAttribute("employees", service.findAll());
        return "employee/list";
    }

    /* 新規フォーム */
    @GetMapping("/new")
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_LIST)")
    public String create(Model model) {
        EmployeeForm form = new EmployeeForm();
        // 曜日行を初期化（OPTIONAL）
        for (short d = 1; d <= 7; d++) {
            EmployeeForm.WeeklyPrefRow row = new EmployeeForm.WeeklyPrefRow();
            row.setDayOfWeek(d);
            row.setWorkStyle("OPTIONAL");
            form.getWeeklyPreferences().add(row);
        }
        model.addAttribute("employeeForm", form);
        model.addAttribute("edit", false);
        model.addAttribute("stores", service.findAllStores());
        return "employee/form";
    }

    /* 編集フォーム */
    @GetMapping("/{code}")
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_LIST)")
    public String edit(@PathVariable String code, Model model) {
        EmployeeForm form = EmployeeForm.from(service.find(code));
        var prefs = service.findWeekly(code);
        form.getWeeklyPreferences().clear();
        java.util.Map<Short, io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference> map = new java.util.HashMap<>();
        for (var p : prefs) map.put(p.getDayOfWeek(), p);
        for (short d = 1; d <= 7; d++) {
            var row = new EmployeeForm.WeeklyPrefRow();
            row.setDayOfWeek(d);
            var p = map.get(d);
            if (p != null) {
                row.setWorkStyle(p.getWorkStyle());
                if (p.getBaseStartTime() != null) row.setBaseStartTime(p.getBaseStartTime().toString());
                if (p.getBaseEndTime() != null) row.setBaseEndTime(p.getBaseEndTime().toString());
            } else {
                row.setWorkStyle("OPTIONAL");
            }
            form.getWeeklyPreferences().add(row);
        }
        model.addAttribute("employeeForm", form);
        model.addAttribute("edit", true);
        model.addAttribute("stores", service.findAllStores());
        return "employee/form";
    }

    /* 保存 */
    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_LIST)")
    public String save(@Valid @ModelAttribute("employeeForm") EmployeeForm form,
                       BindingResult result, @RequestParam("edit") boolean edit, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("edit", edit);
            model.addAttribute("stores", service.findAllStores());
            return "employee/form";
        }
        // Map weekly prefs
        java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference> prefs = new java.util.ArrayList<>();
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm");
        for (var row : form.getWeeklyPreferences()) {
            var p = new io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference();
            p.setDayOfWeek(row.getDayOfWeek());
            p.setWorkStyle(row.getWorkStyle());
            if (!"OFF".equals(row.getWorkStyle())) {
                try {
                    if (row.getBaseStartTime() != null && !row.getBaseStartTime().isBlank())
                        p.setBaseStartTime(new java.sql.Time(sdf.parse(row.getBaseStartTime()).getTime()));
                    if (row.getBaseEndTime() != null && !row.getBaseEndTime().isBlank())
                        p.setBaseEndTime(new java.sql.Time(sdf.parse(row.getBaseEndTime()).getTime()));
                } catch (java.text.ParseException e) {
                    // 入力エラーは無視し保存時はNULL扱い
                }
            }
            prefs.add(p);
        }
        service.save(form.toEntity(), !edit, prefs);
        return "redirect:/employees";
    }

    /* 削除 */
    @PostMapping("/{code}/delete")
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_LIST)")
    public String delete(@PathVariable String code) {
        service.delete(code);
        return "redirect:/employees";
    }
}
