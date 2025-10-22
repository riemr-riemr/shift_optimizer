package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.dto.ShiftAssignmentMonthlyView;
import io.github.riemr.shift.application.dto.ShiftAssignmentView;
import io.github.riemr.shift.application.dto.SolveRequest;
import io.github.riemr.shift.application.dto.SolveStatusDto;
import io.github.riemr.shift.application.dto.SolveTicket;
import io.github.riemr.shift.application.service.StaffingBalanceService;
import io.github.riemr.shift.application.service.ShiftOptimizationPreparationService;
import io.github.riemr.shift.optimization.service.ShiftScheduleService;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.application.dto.RegisterDemandHourDto;
import io.github.riemr.shift.application.service.RegisterDemandHourService;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.infrastructure.persistence.entity.WorkDemandQuarter;
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.mapper.WorkDemandIntervalMapper;
import io.github.riemr.shift.application.util.TimeIntervalQuarterUtils;
import io.github.riemr.shift.application.dto.DemandIntervalDto;
import io.github.riemr.shift.application.dto.QuarterSlot;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeDepartmentMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentMaster;
import io.github.riemr.shift.infrastructure.mapper.StoreDepartmentMapper;
import io.github.riemr.shift.application.dto.ShiftAssignmentSaveRequest;
import io.github.riemr.shift.application.service.AppSettingService;
import io.github.riemr.shift.application.dto.StaffingBalanceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class ShiftCalcController {

    private final ShiftScheduleService service;
    private final StaffingBalanceService staffingBalanceService;
    private final ShiftOptimizationPreparationService preparationService;
    private final StoreMapper storeMapper;
    private final RegisterDemandHourService registerDemandHourService;
    private final RegisterDemandIntervalMapper registerDemandIntervalMapper;
    private final WorkDemandIntervalMapper workDemandIntervalMapper;
    private final EmployeeMapper employeeMapper;
    private final EmployeeDepartmentMapper employeeDepartmentMapper;
    private final StoreDepartmentMapper storeDepartmentMapper;
    private final AppSettingService appSettingService;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    @GetMapping("/calc")
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_DAILY)")
    public String view(Model model) {
        model.addAttribute("timeResolutionMinutes", appSettingService.getTimeResolutionMinutes());
        return "shift/calc";
    }

    @PostMapping("/api/calc/prepare")
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public Map<String, Object> prepare(@RequestBody SolveRequest req) {
        try {
            LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            int startDay = appSettingService.getShiftCycleStartDay();
            LocalDate cycleStart = computeCycleStart(base, startDay);
            
            log.info("Starting optimization preparation for month={}, store={}, dept={}", req.month(), req.storeCode(), req.departmentCode());
            var future = preparationService.prepareOptimizationDataAsync(cycleStart, req.storeCode(), req.departmentCode());
            boolean success = future.get(); // Wait for completion
            
            return Map.of(
                "success", success,
                "message", success ? "事前準備が完了しました" : "事前準備中にエラーがありましたが続行可能です"
            );
        } catch (Exception e) {
            log.error("Failed to prepare optimization data", e);
            return Map.of(
                "success", false,
                "message", "事前準備に失敗しました: " + e.getMessage()
            );
        }
    }

    @PostMapping("/api/calc/start")
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public SolveTicket start(@RequestBody SolveRequest req) {
        LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int startDay = appSettingService.getShiftCycleStartDay();
        LocalDate cycleStart = computeCycleStart(base, startDay);
        
        // 最適化開始: work_demand_interval + register_demand_interval → シフト最適化
        log.info("Starting shift optimization for month={}, store={}, dept={}", req.month(), req.storeCode(), req.departmentCode());
        return service.startSolveMonth(cycleStart, req.storeCode(), req.departmentCode());
    }

    private LocalDate computeCycleStart(LocalDate anyDate, int startDay) {
        int dom = anyDate.getDayOfMonth();
        if (dom >= startDay) {
            int fixed = Math.min(startDay, anyDate.lengthOfMonth());
            return anyDate.withDayOfMonth(fixed);
        } else {
            LocalDate prev = anyDate.minusMonths(1);
            int fixed = Math.min(startDay, prev.lengthOfMonth());
            return prev.withDayOfMonth(fixed);
        }
    }

    @GetMapping("/api/calc/status/{id}")
    @ResponseBody
    public SolveStatusDto status(@PathVariable("id") Long id,
                                 @RequestParam("storeCode") String storeCode,
                                 @RequestParam("departmentCode") String departmentCode) {
        return service.getStatus(id, storeCode, departmentCode);
    }

    @GetMapping("/api/calc/result/{id}")
    @ResponseBody
    public List<ShiftAssignmentView> result(@PathVariable("id") Long id,
                                            @RequestParam("storeCode") String storeCode,
                                            @RequestParam("departmentCode") String departmentCode) {
        return service.fetchResult(id, storeCode, departmentCode);
    }

    @GetMapping("/api/calc/assignments/daily/{date}")
    @ResponseBody
    public List<ShiftAssignmentView> getAssignmentsByDate(@PathVariable("date") String dateString,
                                                          @RequestParam("storeCode") String storeCode,
                                                          @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        return service.fetchAssignmentsByDate(date, storeCode, departmentCode);
    }

    @GetMapping("/api/calc/shifts/monthly/{month}")
    @ResponseBody
    public List<ShiftAssignmentMonthlyView> getShiftsByMonth(@PathVariable("month") String monthString,
                                                             @RequestParam("storeCode") String storeCode,
                                                             @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        LocalDate month = LocalDate.parse(monthString + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return service.fetchShiftsByMonth(month, storeCode, departmentCode);
    }

    @GetMapping("/api/calc/work-model/{date}")
    @ResponseBody
    public List<RegisterDemandHourDto> getWorkModelByDate(@PathVariable("date") String dateString,
                                                          @RequestParam("storeCode") String storeCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        return registerDemandHourService.findHourlyDemands(storeCode, date);
    }

    @GetMapping("/api/calc/work-model-quarter/{date}")
    @ResponseBody
    public List<RegisterDemandQuarter> getWorkModelQuarterByDate(@PathVariable("date") String dateString,
                                                                 @RequestParam("storeCode") String storeCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        var intervals = registerDemandIntervalMapper.selectByStoreAndDate(storeCode, date);
        int resMin = appSettingService.getTimeResolutionMinutes();
        var quarters = TimeIntervalQuarterUtils.splitAll(intervals, resMin);
        List<RegisterDemandQuarter> result = new ArrayList<>(quarters.size());
        for (QuarterSlot qs : quarters) {
            RegisterDemandQuarter ent = new RegisterDemandQuarter();
            ent.setStoreCode(qs.getStoreCode());
            ent.setDemandDate(java.sql.Date.valueOf(qs.getDate()));
            ent.setSlotTime(qs.getStart());
            ent.setRequiredUnits(qs.getDemand());
            result.add(ent);
        }
        return result;
    }

    @GetMapping("/api/calc/work-demand-quarter/{date}")
    @ResponseBody
    public List<WorkDemandQuarter> getWorkDemandQuarterByDate(@PathVariable("date") String dateString,
                                                               @RequestParam("storeCode") String storeCode,
                                                               @RequestParam("departmentCode") String departmentCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        var intervals = workDemandIntervalMapper.selectByDate(storeCode, departmentCode, date);
        List<WorkDemandQuarter> result = new ArrayList<>();
        int resMin = appSettingService.getTimeResolutionMinutes();
        for (DemandIntervalDto di : intervals) {
            var t = di.getFrom();
            while (t.isBefore(di.getTo())) {
                WorkDemandQuarter wq = new WorkDemandQuarter();
                wq.setStoreCode(di.getStoreCode());
                wq.setDepartmentCode(di.getDepartmentCode());
                wq.setDemandDate(java.sql.Date.valueOf(di.getTargetDate()));
                wq.setSlotTime(t);
                wq.setTaskCode(di.getTaskCode());
                wq.setRequiredUnits(di.getDemand());
                result.add(wq);
                t = t.plusMinutes(resMin);
            }
        }
        // sort by time and task for stable output
        result.sort(Comparator.comparing(WorkDemandQuarter::getSlotTime)
                .thenComparing(w -> java.util.Objects.toString(w.getTaskCode(), "")));
        return result;
    }

    @GetMapping("/api/calc/employees/{storeCode}")
    @ResponseBody
    public List<Employee> getEmployeesByStore(@PathVariable("storeCode") String storeCode,
                                              @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        var list = employeeMapper.selectByStoreCode(storeCode);
        if (departmentCode == null || departmentCode.isBlank()) return list;
        var edList = employeeDepartmentMapper.selectByDepartment(departmentCode);
        var allowed = edList.stream().map(ed -> ed.getEmployeeCode()).collect(java.util.stream.Collectors.toSet());
        return list.stream().filter(e -> allowed.contains(e.getEmployeeCode())).toList();
    }

    @GetMapping("/api/departments/{storeCode}")
    @ResponseBody
    public List<DepartmentMaster> getDepartmentsByStore(@PathVariable("storeCode") String storeCode) {
        return storeDepartmentMapper.findDepartmentsByStore(storeCode);
    }

    @PostMapping("/api/calc/assignments/save")
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public Map<String, Object> saveShiftAssignments(@RequestBody ShiftAssignmentSaveRequest request) {
        try {
            service.saveShiftAssignmentChanges(request);
            return Map.of("success", true, "message", "変更が保存されました");
        } catch (Exception e) {
            return Map.of("success", false, "message", "保存中にエラーが発生しました: " + e.getMessage());
        }
    }

    @GetMapping("/api/calc/staffing-balance/{date}")
    @ResponseBody
    public List<StaffingBalanceDto> getStaffingBalance(@PathVariable("date") String dateString,
                                                       @RequestParam("storeCode") String storeCode,
                                                       @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        return staffingBalanceService.getHourlyStaffingBalance(storeCode, date, departmentCode);
    }

    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    public String monthlyShift(@RequestParam(required = false) String targetMonth,
                               @RequestParam(required = false) String storeCode,
                               @RequestParam(required = false) String departmentCode,
                               Model model) {
        try {
            YearMonth yearMonth;
            if (targetMonth == null) {
                yearMonth = YearMonth.now();
            } else {
                yearMonth = YearMonth.parse(targetMonth);
            }
            int startDay = appSettingService.getShiftCycleStartDay();
            LocalDate anyDate = yearMonth.atDay(1);
            LocalDate cycleStart = computeCycleStart(anyDate, startDay);
            LocalDate cycleEnd = cycleStart.plusMonths(1);
            
            // 店舗リストを取得
            List<Store> stores = storeMapper.selectByExample(null);
            stores.sort(Comparator.comparing(Store::getStoreCode));
            
            // 月次シフトデータ取得（部門指定）
            List<ShiftAssignmentMonthlyView> monthlyAssignments = service.fetchAssignmentsByMonth(cycleStart, storeCode, departmentCode);
            
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
                        assignment -> assignment.employeeCode() + "_" + assignment.date().toString(),
                        Collectors.mapping(assignment -> new ShiftInfo(
                            assignment.startAt().format(DateTimeFormatter.ofPattern("HH:mm")),
                            assignment.endAt().format(DateTimeFormatter.ofPattern("HH:mm"))
                        ), Collectors.toList())
                    ));
            }

            // 日別人員配置サマリーを取得
            Map<LocalDate, StaffingBalanceService.DailyStaffingSummary> staffingSummaries = 
                staffingBalanceService.getDailyStaffingSummaryForMonth(storeCode, cycleStart, storeCode == null ? null : (departmentCode == null || departmentCode.isEmpty() ? "520" : departmentCode));
            
            // 日別人時サマリーを作成
            int daysInMonth = (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStart, cycleEnd);
            // 検索フォームと見出しは「ユーザーが選択した月」を表示する
            int year = yearMonth.getYear();
            int month = yearMonth.getMonthValue();
            
            // サイクル期間の日付リストを生成
            List<LocalDate> cycleDateList = java.util.stream.Stream
                    .iterate(cycleStart, d -> d.plusDays(1))
                    .limit(daysInMonth)
                    .toList();
            
            Map<Integer, DailyStaffingSummaryInfo> dailyStaffingInfo = new java.util.HashMap<>();
            for (int day = 1; day <= daysInMonth; day++) {
                LocalDate date = cycleStart.plusDays(day - 1);
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
            model.addAttribute("dateList", cycleDateList);
            model.addAttribute("selectedStoreCode", storeCode);
            model.addAttribute("selectedDepartmentCode", departmentCode);
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
            model.addAttribute("selectedDepartmentCode", departmentCode);
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
