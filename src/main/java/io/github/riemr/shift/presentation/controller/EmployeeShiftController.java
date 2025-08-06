package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.dto.EmployeeMonthlyShiftDto;
import io.github.riemr.shift.application.service.EmployeeShiftService;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 従業員個人シフト表示コントローラー
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/employee-shift")
public class EmployeeShiftController {

    private final EmployeeShiftService employeeShiftService;

    /**
     * 従業員個人シフト表示画面
     */
    @GetMapping
    public String employeeShift(
            @RequestParam(required = false) String employeeCode,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            Model model) {

        // 従業員リストを取得
        List<Employee> employees = employeeShiftService.getAllEmployees();
        model.addAttribute("employees", employees);

        // デフォルト値の設定
        if (year == null || month == null) {
            LocalDate today = LocalDate.now();
            year = today.getYear();
            month = today.getMonthValue();
        }

        model.addAttribute("selectedYear", year);
        model.addAttribute("selectedMonth", month);
        model.addAttribute("selectedEmployeeCode", employeeCode);

        // 従業員が選択されている場合のみシフトデータを取得
        if (employeeCode != null && !employeeCode.isEmpty()) {
            try {
                YearMonth targetMonth = YearMonth.of(year, month);
                EmployeeMonthlyShiftDto monthlyShift = 
                    employeeShiftService.getEmployeeMonthlyShift(employeeCode, targetMonth);
                model.addAttribute("monthlyShift", monthlyShift);
                model.addAttribute("hasShiftData", true);
                
                // 選択された従業員情報を追加
                Employee selectedEmployee = employees.stream()
                    .filter(emp -> emp.getEmployeeCode().equals(employeeCode))
                    .findFirst()
                    .orElse(null);
                model.addAttribute("selectedEmployee", selectedEmployee);
                
            } catch (Exception e) {
                model.addAttribute("errorMessage", "シフトデータの取得に失敗しました: " + e.getMessage());
                model.addAttribute("hasShiftData", false);
            }
        } else {
            model.addAttribute("hasShiftData", false);
        }

        return "employee-shift/index";
    }

    /**
     * 従業員個人シフトのCSVエクスポート
     */
    @GetMapping("/export")
    public String exportEmployeeShift(
            @RequestParam String employeeCode,
            @RequestParam Integer year,
            @RequestParam Integer month,
            Model model) {

        try {
            YearMonth targetMonth = YearMonth.of(year, month);
            EmployeeMonthlyShiftDto monthlyShift = 
                employeeShiftService.getEmployeeMonthlyShift(employeeCode, targetMonth);
            
            model.addAttribute("monthlyShift", monthlyShift);
            return "employee-shift/export-csv";
            
        } catch (Exception e) {
            model.addAttribute("errorMessage", "CSVエクスポートに失敗しました: " + e.getMessage());
            return "redirect:/employee-shift?employeeCode=" + employeeCode 
                    + "&year=" + year + "&month=" + month;
        }
    }
}