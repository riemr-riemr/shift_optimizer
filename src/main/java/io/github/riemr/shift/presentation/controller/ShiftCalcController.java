package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.dto.ShiftAssignmentMonthlyView;
import io.github.riemr.shift.application.dto.ShiftAssignmentView;
import io.github.riemr.shift.application.dto.SolveRequest;
import io.github.riemr.shift.application.dto.SolveStatusDto;
import io.github.riemr.shift.application.dto.SolveTicket;
import io.github.riemr.shift.application.service.StaffingBalanceService;
import io.github.riemr.shift.optimization.service.ShiftScheduleService;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.application.dto.RegisterDemandHourDto;
import io.github.riemr.shift.application.service.RegisterDemandHourService;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandQuarterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.application.dto.ShiftAssignmentSaveRequest;
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
    private final StoreMapper storeMapper;
    private final RegisterDemandHourService registerDemandHourService;
    private final RegisterDemandQuarterMapper registerDemandQuarterMapper;
    private final EmployeeMapper employeeMapper;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    @GetMapping("/calc")
    public String view() {
        return "shift/calc";
    }

    @PostMapping("/api/calc/start")
    @ResponseBody
    public SolveTicket start(@RequestBody SolveRequest req) {
        LocalDate month = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return service.startSolveMonth(month, req.storeCode());
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

    @GetMapping("/api/calc/work-model/{date}")
    @ResponseBody
    public List<RegisterDemandHourDto> getWorkModelByDate(@PathVariable("date") String dateString) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        // TODO: 店舗コードは将来的にはパラメータから取得
        return registerDemandHourService.findHourlyDemands("569", date);
    }

    @GetMapping("/api/calc/work-model-quarter/{date}")
    @ResponseBody
    public List<RegisterDemandQuarter> getWorkModelQuarterByDate(@PathVariable("date") String dateString) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        
        // RegisterDemandQuarterExampleを使用してクエリ条件を設定
        var example = new io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarterExample();
        example.createCriteria()
            .andStoreCodeEqualTo("569")
            .andDemandDateEqualTo(java.sql.Date.valueOf(date));
        
        return registerDemandQuarterMapper.selectByExample(example);
    }

    @GetMapping("/api/calc/employees/{storeCode}")
    @ResponseBody
    public List<Employee> getEmployeesByStore(@PathVariable("storeCode") String storeCode) {
        return employeeMapper.selectByStoreCode(storeCode);
    }

    @PostMapping("/api/calc/assignments/save")
    @ResponseBody
    public Map<String, Object> saveShiftAssignments(@RequestBody ShiftAssignmentSaveRequest request) {
        try {
            service.saveShiftAssignmentChanges(request);
            return Map.of("success", true, "message", "変更が保存されました");
        } catch (Exception e) {
            return Map.of("success", false, "message", "保存中にエラーが発生しました: " + e.getMessage());
        }
    }

    @GetMapping
    public String monthlyShift(@RequestParam(required = false) String targetMonth, 
                              @RequestParam(required = false) String storeCode, 
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
            
            // 店舗リストを取得
            List<Store> stores = storeMapper.selectByExample(null);
            stores.sort(Comparator.comparing(Store::getStoreCode));
            
            // 月次シフトデータ取得
            List<ShiftAssignmentMonthlyView> monthlyAssignments = service.fetchAssignmentsByMonth(yearMonth.atDay(1));
            
            // 従業員一覧を作成（店舗でフィルタリング）
            List<EmployeeInfo> employees = new ArrayList<>();
            Map<String, List<ShiftInfo>> employeeShifts = Map.of();
            
            if (monthlyAssignments != null && !monthlyAssignments.isEmpty()) {
                final String finalStoreCode = storeCode; // Make effectively final for lambda
                
                Set<String> employeeCodes = monthlyAssignments.stream()
                    .map(ShiftAssignmentMonthlyView::employeeCode)
                    .collect(Collectors.toSet());
                
                List<EmployeeInfo> filteredEmployees = employeeCodes.stream()
                    .map(code -> {
                        String name = monthlyAssignments.stream()
                            .filter(a -> a.employeeCode().equals(code))
                            .findFirst()
                            .map(ShiftAssignmentMonthlyView::employeeName)
                            .orElse(code);
                        return new EmployeeInfo(code, name);
                    })
                    .filter(emp -> {
                        // 店舗フィルタリング：storeCodeが指定されている場合のみ
                        if (finalStoreCode == null || finalStoreCode.isEmpty()) {
                            return false; // 店舗未選択時は従業員を表示しない
                        }
                        // TODO: 実際の従業員-店舗マッピングでフィルタリング
                        return true;
                    })
                    .sorted(Comparator.comparing(EmployeeInfo::employeeCode))
                    .collect(Collectors.toList());
                
                employees = filteredEmployees; // Assign to final variable
                
                // Get employee codes for filtering shifts
                final Set<String> finalEmployeeCodes = employees.stream()
                    .map(EmployeeInfo::employeeCode)
                    .collect(Collectors.toSet());
                
                // 従業員別・日別のシフトデータを作成
                employeeShifts = monthlyAssignments.stream()
                    .filter(assignment -> {
                        // 店舗フィルタリング適用
                        if (finalStoreCode == null || finalStoreCode.isEmpty()) {
                            return false;
                        }
                        return finalEmployeeCodes.contains(assignment.employeeCode());
                    })
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
            model.addAttribute("stores", stores);
            model.addAttribute("selectedStoreCode", storeCode);
            model.addAttribute("employees", employees);
            model.addAttribute("employeeShifts", employeeShifts);
            model.addAttribute("dailyStaffingInfo", dailyStaffingInfo);
            
            return "shift/monthly-shift";
        } catch (Exception e) {
            // エラーハンドリング
            YearMonth currentMonth = YearMonth.now();
            List<Store> stores = storeMapper.selectByExample(null);
            stores.sort(Comparator.comparing(Store::getStoreCode));
            
            model.addAttribute("year", currentMonth.getYear());
            model.addAttribute("month", currentMonth.getMonthValue());
            model.addAttribute("daysInMonth", currentMonth.lengthOfMonth());
            model.addAttribute("stores", stores);
            model.addAttribute("selectedStoreCode", storeCode);
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