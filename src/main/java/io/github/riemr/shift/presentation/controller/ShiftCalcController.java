package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.dto.ShiftAssignmentMonthlyView;
import io.github.riemr.shift.application.dto.ShiftAssignmentView;
import io.github.riemr.shift.application.dto.SolveRequest;
import io.github.riemr.shift.application.dto.SolveStatusDto;
import io.github.riemr.shift.application.dto.SolveTicket;
import io.github.riemr.shift.application.service.StaffingBalanceService;
import io.github.riemr.shift.optimization.service.ShiftScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
@RequestMapping("/shift")
public class ShiftCalcController {

    private final ShiftScheduleService service;
    private final StaffingBalanceService staffingBalanceService;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    @GetMapping("/calc")
    public String view() {
        return "shift/calc";
    }

    @PostMapping("/api/calc/start")
    @ResponseBody
    public SolveTicket start(@RequestBody SolveRequest req) {
        LocalDate month = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return service.startSolveMonth(month);
    }

    @GetMapping("/api/calc/status/{id}")
    @ResponseBody
    public SolveStatusDto status(@PathVariable("id") Long id) {
        return service.getStatus(id);
    }

    @GetMapping("/api/calc/result/{id}")
    @ResponseBody
    public List<ShiftAssignmentView> result(@PathVariable("id") Long id) {
        return service.fetchResult(id);
    }

    @GetMapping("/api/calc/assignments/daily/{date}")
    @ResponseBody
    public List<ShiftAssignmentView> getAssignmentsByDate(@PathVariable("date") String dateString) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        return service.fetchAssignmentsByDate(date);
    }

    @GetMapping("/api/calc/shifts/monthly/{month}")
    @ResponseBody
    public List<ShiftAssignmentMonthlyView> getShiftsByMonth(@PathVariable("month") String monthString) {
        LocalDate month = LocalDate.parse(monthString + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return service.fetchShiftsByMonth(month);
    }

    @GetMapping
    public String monthlyShift(@RequestParam(required = false) String targetMonth, 
                              @RequestParam(defaultValue = "569") String storeCode, 
                              Model model) {
        try {
            YearMonth yearMonth;
            if (targetMonth == null) {
                yearMonth = YearMonth.now();
            } else {
                yearMonth = YearMonth.parse(targetMonth);
            }
            int year = yearMonth.getYear();
            int month = yearMonth.getMonthValue();
            int daysInMonth = yearMonth.lengthOfMonth();
            
            // 月次シフトデータ取得
            List<ShiftAssignmentMonthlyView> monthlyAssignments = service.fetchAssignmentsByMonth(yearMonth.atDay(1));
            
            // 従業員一覧を作成
            List<EmployeeInfo> employees = new ArrayList<>();
            Map<String, List<ShiftInfo>> employeeShifts = Map.of();
            
            if (monthlyAssignments != null && !monthlyAssignments.isEmpty()) {
                Set<String> employeeCodes = monthlyAssignments.stream()
                    .map(ShiftAssignmentMonthlyView::employeeCode)
                    .collect(Collectors.toSet());
                
                employees = employeeCodes.stream()
                    .map(code -> {
                        String name = monthlyAssignments.stream()
                            .filter(a -> a.employeeCode().equals(code))
                            .findFirst()
                            .map(ShiftAssignmentMonthlyView::employeeName)
                            .orElse(code);
                        return new EmployeeInfo(code, name);
                    })
                    .sorted(Comparator.comparing(EmployeeInfo::employeeCode))
                    .collect(Collectors.toList());
                
                // 従業員別・日別のシフトデータを作成
                employeeShifts = monthlyAssignments.stream()
                    .collect(Collectors.groupingBy(
                        assignment -> assignment.employeeCode() + "_" + assignment.date().getDayOfMonth(),
                        Collectors.mapping(assignment -> new ShiftInfo(
                            assignment.startAt().format(DateTimeFormatter.ofPattern("HH:mm")),
                            assignment.endAt().format(DateTimeFormatter.ofPattern("HH:mm"))
                        ), Collectors.toList())
                    ));
            }

            // 日別人員配置サマリーを取得
            Map<LocalDate, StaffingBalanceService.DailyStaffingSummary> staffingSummaries = 
                staffingBalanceService.getDailyStaffingSummaryForMonth(storeCode, yearMonth.atDay(1));
            
            // 日別人時サマリーを作成
            Map<Integer, DailyStaffingSummaryInfo> dailyStaffingInfo = new java.util.HashMap<>();
            for (int day = 1; day <= daysInMonth; day++) {
                LocalDate date = yearMonth.atDay(day);
                StaffingBalanceService.DailyStaffingSummary summary = staffingSummaries.get(date);
                
                if (summary != null) {
                    dailyStaffingInfo.put(day, new DailyStaffingSummaryInfo(
                        summary.getTotalRequired(),
                        summary.getTotalAssigned(),
                        summary.getBalance()
                    ));
                } else {
                    dailyStaffingInfo.put(day, new DailyStaffingSummaryInfo(0, 0, 0));
                }
            }

            model.addAttribute("year", year);
            model.addAttribute("month", month);
            model.addAttribute("daysInMonth", daysInMonth);
            model.addAttribute("storeCode", storeCode);
            model.addAttribute("employees", employees);
            model.addAttribute("employeeShifts", employeeShifts);
            model.addAttribute("dailyStaffingInfo", dailyStaffingInfo);
            
            return "shift/monthly-shift";
        } catch (Exception e) {
            // エラーハンドリング
            YearMonth currentMonth = YearMonth.now();
            model.addAttribute("year", currentMonth.getYear());
            model.addAttribute("month", currentMonth.getMonthValue());
            model.addAttribute("daysInMonth", currentMonth.lengthOfMonth());
            model.addAttribute("storeCode", storeCode);
            model.addAttribute("employees", List.of());
            model.addAttribute("employeeShifts", Map.of());
            model.addAttribute("dailyStaffingInfo", Map.of());
            model.addAttribute("error", "データ取得中にエラーが発生しました: " + e.getMessage());
            
            return "shift/monthly-shift";
        }
    }
    
    // 内部クラスでEmployeeInfoを定義
    public static class EmployeeInfo {
        private final String employeeCode;
        private final String employeeName;
        
        public EmployeeInfo(String employeeCode, String employeeName) {
            this.employeeCode = employeeCode;
            this.employeeName = employeeName;
        }
        
        public String getEmployeeCode() { return employeeCode; }
        public String getEmployeeName() { return employeeName; }
        public String employeeCode() { return employeeCode; }
        public String employeeName() { return employeeName; }
    }
    
    // 内部クラスでShiftInfoを定義
    public static class ShiftInfo {
        private final String startTime;
        private final String endTime;
        
        public ShiftInfo(String startTime, String endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
        }
        
        public String getStartTime() { return startTime; }
        public String getEndTime() { return endTime; }
        public String startTime() { return startTime; }
        public String endTime() { return endTime; }
    }
    
    // 内部クラスで日別人員配置サマリーを定義
    public static class DailyStaffingSummaryInfo {
        private final int totalRequired;
        private final int totalAssigned;
        private final int balance;
        
        public DailyStaffingSummaryInfo(int totalRequired, int totalAssigned, int balance) {
            this.totalRequired = totalRequired;
            this.totalAssigned = totalAssigned;
            this.balance = balance;
        }
        
        public int getTotalRequired() { return totalRequired; }
        public int getTotalAssigned() { return totalAssigned; }
        public int getBalance() { return balance; }
        public int totalRequired() { return totalRequired; }
        public int totalAssigned() { return totalAssigned; }
        public int balance() { return balance; }
    }
}