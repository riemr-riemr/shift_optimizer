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
    private final io.github.riemr.shift.infrastructure.mapper.EmployeeMonthlyHoursSettingMapper monthlyHoursMapper;
    private final io.github.riemr.shift.infrastructure.mapper.EmployeeMonthlyOffdaysSettingMapper monthlyOffdaysMapper;

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
        // 週次初期値
        form.setMinWorkHoursWeek(0);
        form.setMaxWorkHoursWeek(50);
        // 月次設定の初期行（3行空行）
        for (int i = 0; i < 3; i++) form.getMonthlyHours().add(new EmployeeForm.MonthlyHoursRow());
        // 年選択の初期値（今年）
        form.setSelectedYear(java.time.Year.now().getValue());
        model.addAttribute("employeeForm", form);
        model.addAttribute("edit", false);
        model.addAttribute("stores", service.findAllStores());
        model.addAttribute("availableYears", getAvailableYears());
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
        // 月次設定をロード
        var monthlyList = new java.util.ArrayList<EmployeeForm.MonthlyHoursRow>();
        var monthlyEntities = monthlyHoursMapper.selectByEmployee(code);
        for (var m : monthlyEntities) {
            var r = new EmployeeForm.MonthlyHoursRow();
            java.time.LocalDate md = m.getMonthStart().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            r.setMonth(md.toString().substring(0,7));
            r.setMinHours(m.getMinWorkHours());
            r.setMaxHours(m.getMaxWorkHours());
            monthlyList.add(r);
        }
        while (monthlyList.size() < 3) monthlyList.add(new EmployeeForm.MonthlyHoursRow());
        form.setMonthlyHours(monthlyList);
        
        // 年選択の初期値設定とテーブル形式データ変換
        Integer currentYear = java.time.Year.now().getValue();
        form.setSelectedYear(currentYear);
        loadMonthlyHoursForYear(form, code, currentYear);
        loadMonthlyOffdaysForYear(form, code, currentYear);
        
        model.addAttribute("employeeForm", form);
        model.addAttribute("edit", true);
        model.addAttribute("stores", service.findAllStores());
        model.addAttribute("availableYears", getAvailableYears());
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
            model.addAttribute("availableYears", getAvailableYears());
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
        // Map monthly work-hours settings from table format
        java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyHoursSetting> monthly = new java.util.ArrayList<>();
        if (form.getSelectedYear() != null && form.getMonthlyHoursTable() != null) {
            for (int month = 1; month <= 12; month++) {
                Integer minHours = form.getMonthlyHoursTable().getMinHours()[month - 1];
                Integer maxHours = form.getMonthlyHoursTable().getMaxHours()[month - 1];
                if (minHours != null || maxHours != null) {
                    var m = new io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyHoursSetting();
                    java.time.YearMonth ym = java.time.YearMonth.of(form.getSelectedYear(), month);
                    java.util.Date ms = java.sql.Date.valueOf(ym.atDay(1));
                    m.setMonthStart(ms);
                    m.setMinWorkHours(minHours);
                    m.setMaxWorkHours(maxHours);
                    monthly.add(m);
                }
            }
        }
        // Map monthly off-days settings from table format
        java.util.List<io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyOffdaysSetting> offdays = new java.util.ArrayList<>();
        if (form.getSelectedYear() != null && form.getMonthlyOffdaysTable() != null) {
            for (int month = 1; month <= 12; month++) {
                Integer minOff = form.getMonthlyOffdaysTable().getMinOffDays()[month - 1];
                Integer maxOff = form.getMonthlyOffdaysTable().getMaxOffDays()[month - 1];
                if (minOff != null || maxOff != null) {
                    var m = new io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyOffdaysSetting();
                    java.time.YearMonth ym = java.time.YearMonth.of(form.getSelectedYear(), month);
                    java.util.Date ms = java.sql.Date.valueOf(ym.atDay(1));
                    m.setMonthStart(ms);
                    m.setMinOffDays(minOff);
                    m.setMaxOffDays(maxOff);
                    offdays.add(m);
                }
            }
        }
        service.save(form.toEntity(), !edit, prefs, monthly, offdays);
        return "redirect:/employees";
    }

    /* 削除 */
    @PostMapping("/{code}/delete")
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_LIST)")
    public String delete(@PathVariable String code) {
        service.delete(code);
        return "redirect:/employees";
    }
    
    /* Ajax: 指定年の月別勤務時間を取得 */
    @GetMapping("/{code}/monthly-hours/{year}")
    @ResponseBody
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_LIST)")
    public java.util.Map<String, Object> getMonthlyHoursByYear(@PathVariable String code, @PathVariable Integer year) {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        
        try {
            var monthlyEntities = monthlyHoursMapper.selectByEmployee(code);
            java.util.Map<Integer, io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyHoursSetting> monthMap = new java.util.HashMap<>();
            
            for (var entity : monthlyEntities) {
                java.time.LocalDate date = entity.getMonthStart().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                if (date.getYear() == year) {
                    monthMap.put(date.getMonthValue(), entity);
                }
            }
            
            Integer[] minHours = new Integer[12];
            Integer[] maxHours = new Integer[12];
            
            for (int month = 1; month <= 12; month++) {
                var setting = monthMap.get(month);
                if (setting != null) {
                    minHours[month - 1] = setting.getMinWorkHours();
                    maxHours[month - 1] = setting.getMaxWorkHours();
                }
            }
            
            response.put("success", true);
            response.put("minHours", minHours);
            response.put("maxHours", maxHours);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        
        return response;
    }
    
    /* Ajax: 指定年の月別公休日数を取得 */
    @GetMapping("/{code}/monthly-offdays/{year}")
    @ResponseBody
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_LIST)")
    public java.util.Map<String, Object> getMonthlyOffdaysByYear(@PathVariable String code, @PathVariable Integer year) {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        try {
            var monthlyEntities = monthlyOffdaysMapper.selectByEmployee(code);
            java.util.Map<Integer, io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlyOffdaysSetting> monthMap = new java.util.HashMap<>();
            for (var entity : monthlyEntities) {
                java.time.LocalDate date = entity.getMonthStart().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                if (date.getYear() == year) {
                    monthMap.put(date.getMonthValue(), entity);
                }
            }
            Integer[] minOff = new Integer[12];
            Integer[] maxOff = new Integer[12];
            for (int month = 1; month <= 12; month++) {
                var setting = monthMap.get(month);
                if (setting != null) {
                    minOff[month - 1] = setting.getMinOffDays();
                    maxOff[month - 1] = setting.getMaxOffDays();
                }
            }
            response.put("success", true);
            response.put("minOffDays", minOff);
            response.put("maxOffDays", maxOff);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }
    
    // ビュー描画用: 指定年の月別勤務時間をフォームのテーブルに反映
    private void loadMonthlyHoursForYear(EmployeeForm form, String code, Integer year) {
        var monthlyEntities = monthlyHoursMapper.selectByEmployee(code);
        Integer[] minHours = new Integer[12];
        Integer[] maxHours = new Integer[12];
        for (var entity : monthlyEntities) {
            java.time.LocalDate date = entity.getMonthStart().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (date.getYear() == year) {
                minHours[date.getMonthValue() - 1] = entity.getMinWorkHours();
                maxHours[date.getMonthValue() - 1] = entity.getMaxWorkHours();
            }
        }
        var table = new EmployeeForm.MonthlyHoursTableData();
        table.setMinHours(minHours);
        table.setMaxHours(maxHours);
        form.setMonthlyHoursTable(table);
    }
    
    // ビュー描画用: 指定年の月別公休日数をフォームのテーブルに反映
    private void loadMonthlyOffdaysForYear(EmployeeForm form, String code, Integer year) {
        var entities = monthlyOffdaysMapper.selectByEmployee(code);
        Integer[] minOff = new Integer[12];
        Integer[] maxOff = new Integer[12];
        for (var e : entities) {
            java.time.LocalDate date = e.getMonthStart().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (date.getYear() == year) {
                minOff[date.getMonthValue() - 1] = e.getMinOffDays();
                maxOff[date.getMonthValue() - 1] = e.getMaxOffDays();
            }
        }
        var table = new EmployeeForm.MonthlyOffdaysTableData();
        table.setMinOffDays(minOff);
        table.setMaxOffDays(maxOff);
        form.setMonthlyOffdaysTable(table);
    }
    
    // 年選択オプションを生成（システム年の+-1年）
    private java.util.List<Integer> getAvailableYears() {
        Integer currentYear = java.time.Year.now().getValue();
        return java.util.Arrays.asList(currentYear - 1, currentYear, currentYear + 1);
    }
    
    // （重複回避）上のloadMonthlyHoursForYearを使用
}
