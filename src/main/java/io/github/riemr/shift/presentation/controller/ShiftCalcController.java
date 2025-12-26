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
import io.github.riemr.shift.optimization.entity.RegisterDemandSlot;
import io.github.riemr.shift.optimization.entity.WorkDemandSlot;
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.mapper.WorkDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.mapper.RegisterAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.DepartmentTaskAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
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
import io.github.riemr.shift.application.dto.ScorePoint;
import io.github.riemr.shift.application.dto.DailySolveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.ZoneId;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.temporal.ChronoUnit;
import java.sql.Date;

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
    private final RegisterAssignmentMapper registerAssignmentMapper;
    private final DepartmentTaskAssignmentMapper departmentTaskAssignmentMapper;
    private final ShiftAssignmentMapper shiftAssignmentMapper;
    private final EmployeeMapper employeeMapper;
    private final EmployeeDepartmentMapper employeeDepartmentMapper;
    private final StoreDepartmentMapper storeDepartmentMapper;
    private final AppSettingService appSettingService;

    @GetMapping("/calc")
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_DAILY)")
    public String view(Model model) {
        List<Store> stores = storeMapper.selectByExample(null);
        stores.sort(Comparator.comparing(Store::getStoreCode));
        model.addAttribute("stores", stores);
        model.addAttribute("timeResolutionMinutes", appSettingService.getTimeResolutionMinutes());
        return "shift/calc";
    }

    @PostMapping("/api/calc/prepare")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
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
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public SolveTicket start(@RequestBody SolveRequest req) {
        LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int startDay = appSettingService.getShiftCycleStartDay();
        LocalDate cycleStart = computeCycleStart(base, startDay);
        
        log.info("Starting shift optimization for month={}, store={}, dept={}", req.month(), req.storeCode(), req.departmentCode());
        System.out.println("DEBUG: Received request - month: " + req.month() + ", storeCode: " + req.storeCode() + ", departmentCode: " + req.departmentCode());
        
        // 事前準備処理はShiftScheduleService内で実行されるため、ここでは実行しない
        
        // 既存の最適化（作業割当まで）
        return service.startSolveMonth(cycleStart, req.storeCode(), req.departmentCode());
    }

    // 新規: 月次シフト最適化（出勤のみ決定）
    @PostMapping("/api/attendance/start")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public SolveTicket startAttendance(@RequestBody SolveRequest req) {
        LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int startDay = appSettingService.getShiftCycleStartDay();
        LocalDate cycleStart = computeCycleStart(base, startDay);
        log.info("Starting attendance optimization for month={}, store={}, dept={}", req.month(), req.storeCode(), req.departmentCode());
        return service.startSolveAttendanceMonth(cycleStart, req.storeCode(), req.departmentCode());
    }

    // 新規: 作業割当（出勤済み前提で細目割当）
    @PostMapping("/api/assignment/start")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public SolveTicket startAssignment(@RequestBody SolveRequest req) {
        LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int startDay = appSettingService.getShiftCycleStartDay();
        LocalDate cycleStart = computeCycleStart(base, startDay);
        log.info("Starting assignment optimization for month={}, store={}, dept={}", req.month(), req.storeCode(), req.departmentCode());
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
    public SolveStatusDto status(@PathVariable("id") String id,
                                 @RequestParam("storeCode") String storeCode,
                                 @RequestParam("departmentCode") String departmentCode,
                                 @RequestParam(value = "stage", required = false) String stage) {
        return (stage == null || stage.isBlank())
                ? service.getStatus(id, storeCode, departmentCode)
                : service.getStatus(id, storeCode, departmentCode, stage);
    }

    @GetMapping("/api/calc/result/{id}")
    @ResponseBody
    public List<ShiftAssignmentView> result(@PathVariable("id") String id,
                                            @RequestParam("storeCode") String storeCode,
                                            @RequestParam("departmentCode") String departmentCode,
                                            @RequestParam(value = "stage", required = false) String stage) {
        return (stage == null || stage.isBlank())
                ? service.fetchResult(id, storeCode, departmentCode)
                : service.fetchResult(id, storeCode, departmentCode, stage);
    }

    // 開発者向け: スコア推移を返す
    @GetMapping("/api/calc/score-series/{id}")
    @ResponseBody
    public List<ScorePoint> scoreSeries(@PathVariable("id") String id,
                                                                              @RequestParam("storeCode") String storeCode,
                                                                              @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        return service.getScoreSeries(id, storeCode, departmentCode);
    }

    @GetMapping("/api/calc/assignments/daily/{date}")
    @ResponseBody
    public List<ShiftAssignmentView> getAssignmentsByDate(@PathVariable("date") String dateString,
                                                          @RequestParam("storeCode") String storeCode,
                                                          @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        var zone = ZoneId.systemDefault();
        var from = date;
        var to = date.plusDays(1);

        List<ShiftAssignmentView> results = new ArrayList<>();

        // 従業員名マップ
        var employees = employeeMapper.selectByStoreCode(storeCode);
        Map<String, String> empName = employees.stream().collect(Collectors.toMap(
                Employee::getEmployeeCode,
                Employee::getEmployeeName,
                (a,b) -> a));

        boolean isRegisterDept = (departmentCode == null || departmentCode.isBlank() || "520".equalsIgnoreCase(departmentCode));
        if (isRegisterDept) {
            var reg = registerAssignmentMapper.selectByDate(from, to).stream()
                    .filter(r -> storeCode.equals(r.getStoreCode()))
                    .toList();
            for (var a : reg) {
                String code = a.getEmployeeCode();
                String name = (code == null) ? null : empName.getOrDefault(code, code);
                results.add(new ShiftAssignmentView(
                        a.getStartAt().toInstant().atZone(zone).toLocalDateTime().toString(),
                        a.getEndAt().toInstant().atZone(zone).toLocalDateTime().toString(),
                        a.getRegisterNo(),
                        "520",
                        "REGISTER_OP",
                        null,
                        code,
                        name
                ));
            }
        }

        if (departmentCode != null && !departmentCode.isBlank() && !"520".equalsIgnoreCase(departmentCode)) {
            var tasks = departmentTaskAssignmentMapper.selectByDate(storeCode, departmentCode, from, to);
            for (var t : tasks) {
                String code = t.getEmployeeCode();
                String name = (code == null) ? null : empName.getOrDefault(code, code);
                results.add(new ShiftAssignmentView(
                        t.getStartAt().toInstant().atZone(zone).toLocalDateTime().toString(),
                        t.getEndAt().toInstant().atZone(zone).toLocalDateTime().toString(),
                        null,
                        departmentCode,
                        "DEPARTMENT_TASK",
                        t.getTaskCode(),
                        code,
                        name
                ));
            }
        }

        results.sort(Comparator.comparing(ShiftAssignmentView::startAt));
        return results;
    }

    @GetMapping("/api/calc/shifts/monthly/{ym}")
    @ResponseBody
    public List<ShiftAssignmentMonthlyView> getMonthlyShifts(@PathVariable("ym") String yearMonth,
                                                             @RequestParam("storeCode") String storeCode,
                                                             @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        var ym = YearMonth.parse(yearMonth);
        var from = ym.atDay(1);
        var to = ym.plusMonths(1).atDay(1);

        // 従業員名マップ
        var employees = employeeMapper.selectByStoreCode(storeCode);
        Map<String, String> empName = employees.stream().collect(Collectors.toMap(
                Employee::getEmployeeCode,
                Employee::getEmployeeName,
                (a,b) -> a));

        // 出勤（shift_assignment）を返す：部門指定は関係なく、店舗ベースで集計
        var shifts = shiftAssignmentMapper.selectByMonth(from, to).stream()
                .filter(s -> storeCode.equals(s.getStoreCode()))
                .toList();
        List<ShiftAssignmentMonthlyView> out = new ArrayList<>(shifts.size());
        for (var s : shifts) {
            String code = s.getEmployeeCode();
            String name = (code == null) ? null : empName.getOrDefault(code, code);
            out.add(new ShiftAssignmentMonthlyView(
                    s.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    s.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    null,
                    code,
                    name
            ));
        }
        return out;
    }
    

    @GetMapping("/api/calc/work-model/{date}")
    @ResponseBody
    public List<RegisterDemandHourDto> getWorkModelByDate(@PathVariable("date") String dateString,
                                                          @RequestParam("storeCode") String storeCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        return registerDemandHourService.findHourlyDemands(storeCode, date);
    }

    @GetMapping("/api/calc/work-model-slot/{date}")
    @ResponseBody
    public List<RegisterDemandSlot> getWorkModelSlotsByDate(@PathVariable("date") String dateString,
                                                            @RequestParam("storeCode") String storeCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        var intervals = registerDemandIntervalMapper.selectByStoreAndDate(storeCode, date);
        int resMin = appSettingService.getTimeResolutionMinutes();
        var quarters = TimeIntervalQuarterUtils.splitAll(intervals, resMin);
        List<RegisterDemandSlot> result = new ArrayList<>(quarters.size());
        for (QuarterSlot qs : quarters) {
            RegisterDemandSlot slot = new RegisterDemandSlot();
            slot.setStoreCode(qs.getStoreCode());
            slot.setDemandDate(qs.getDate());
            slot.setSlotTime(qs.getStart());
            slot.setRequiredUnits(qs.getDemand());
            slot.setRegisterNo(qs.getRegisterNo());
            result.add(slot);
        }
        return result;
    }

    @GetMapping("/api/calc/work-demand-slot/{date}")
    @ResponseBody
    public List<WorkDemandSlot> getWorkDemandSlotsByDate(@PathVariable("date") String dateString,
                                                         @RequestParam("storeCode") String storeCode,
                                                         @RequestParam("departmentCode") String departmentCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        var intervals = workDemandIntervalMapper.selectByDate(storeCode, departmentCode, date);
        List<WorkDemandSlot> result = new ArrayList<>();
        int resMin = appSettingService.getTimeResolutionMinutes();
        for (DemandIntervalDto di : intervals) {
            var t = di.getFrom();
            while (t.isBefore(di.getTo())) {
                WorkDemandSlot slot = new WorkDemandSlot();
                slot.setStoreCode(di.getStoreCode());
                slot.setDepartmentCode(di.getDepartmentCode());
                slot.setDemandDate(di.getTargetDate());
                slot.setSlotTime(t);
                slot.setTaskCode(di.getTaskCode());
                slot.setRequiredUnits(di.getDemand());
                result.add(slot);
                t = t.plusMinutes(resMin);
            }
        }
        // sort by time and task for stable output
        result.sort(Comparator.comparing(WorkDemandSlot::getSlotTime)
                .thenComparing(w -> Objects.toString(w.getTaskCode(), "")));
        return result;
    }

    /**
     * 指定月の範囲を日次でASSIGNMENT最適化する（出勤は変更しない）。
     */
    @PostMapping("/api/assignment/start-daily")
    @ResponseBody
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    public Map<String, Object> startAssignmentDaily(@RequestBody SolveRequest req) {
        try {
            LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            int startDay = appSettingService.getShiftCycleStartDay();
            LocalDate cycleStart = computeCycleStart(base, startDay);
            int processed = service.startSolveAssignmentDaily(cycleStart, req.storeCode(), req.departmentCode());
            return Map.of("success", true, "processedDays", processed);
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 単一日付のASSIGNMENT最適化を実行し、当日分のみ保存する。
     */
    @PostMapping("/api/assignment/start-day")
    @ResponseBody
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    public Map<String, Object> startAssignmentForDay(@RequestBody DailySolveRequest req) {
        try {
            LocalDate date = LocalDate.parse(req.date(), DateTimeFormatter.ISO_LOCAL_DATE);
            boolean ok = service.startSolveAssignmentForDate(date, req.storeCode(), req.departmentCode());
            return Map.of("success", ok, "date", req.date());
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @GetMapping("/api/calc/employees/{storeCode}")
    @ResponseBody
    public List<Employee> getEmployeesByStore(@PathVariable("storeCode") String storeCode,
                                              @RequestParam("date") String dateString,
                                              @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        // 指定日の出勤者のみ（shift_assignment）
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        var from = date;
        var to = date.plusDays(1);
        var attendances = shiftAssignmentMapper.selectByDate(from, to).stream()
                .filter(a -> storeCode.equals(a.getStoreCode()))
                .toList();
        Set<String> attendEmp = attendances.stream()
                .map(a -> a.getEmployeeCode())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (attendEmp.isEmpty()) return List.of();

        var list = employeeMapper.selectByStoreCode(storeCode).stream()
                .filter(e -> attendEmp.contains(e.getEmployeeCode()))
                .collect(Collectors.toList());

        // 部門指定（非レジ部門）の場合は部門所属者でさらにフィルタ
        if (departmentCode != null && !departmentCode.isBlank() && !"520".equalsIgnoreCase(departmentCode)) {
            var edList = employeeDepartmentMapper.selectByDepartment(departmentCode);
            var allowed = edList.stream().map(ed -> ed.getEmployeeCode()).collect(Collectors.toSet());
            list = list.stream().filter(e -> allowed.contains(e.getEmployeeCode())).toList();
        }

        // 安定出力のため社員コードでソート
        list.sort(Comparator.comparing(Employee::getEmployeeCode));
        return list;
    }

    @GetMapping("/api/departments/{storeCode}")
    @ResponseBody
    public List<DepartmentMaster> getDepartmentsByStore(@PathVariable("storeCode") String storeCode) {
        return storeDepartmentMapper.findDepartmentsByStore(storeCode);
    }

    @PostMapping("/api/calc/assignments/save")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public Map<String, Object> saveShiftAssignments(@RequestBody ShiftAssignmentSaveRequest request) {
        try {
            service.saveShiftAssignmentChanges(request);
            return Map.of("success", true, "message", "変更が保存されました");
        } catch (Exception e) {
            return Map.of("success", false, "message", "保存中にエラーが発生しました: " + e.getMessage());
        }
    }

    @PostMapping("/api/clear/attendance")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public Map<String, Object> clearAttendance(@RequestBody SolveRequest req) {
        try {
            LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            int startDay = appSettingService.getShiftCycleStartDay();
            LocalDate cycleStart = computeCycleStart(base, startDay);
            int deleted = service.clearAttendance(cycleStart, req.storeCode());
            return Map.of("success", true, "deleted", deleted, "message", "出勤データを削除しました");
        } catch (Exception e) {
            return Map.of("success", false, "message", "削除に失敗しました: " + e.getMessage());
        }
    }

    @PostMapping("/api/clear/assignment")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public Map<String, Object> clearAssignment(@RequestBody SolveRequest req) {
        try {
            LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            int startDay = appSettingService.getShiftCycleStartDay();
            LocalDate cycleStart = computeCycleStart(base, startDay);
            int deleted = service.clearWorkAssignments(cycleStart, req.storeCode(), req.departmentCode());
            return Map.of("success", true, "deleted", deleted, "message", "作業割当を削除しました");
        } catch (Exception e) {
            return Map.of("success", false, "message", "削除に失敗しました: " + e.getMessage());
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
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
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
            
            // 月次シフトデータ取得（出勤＝shift_assignmentベース）
            List<ShiftAssignmentMonthlyView> monthlyAssignments = service.fetchShiftsByMonth(cycleStart, storeCode, departmentCode);
            
            // 従業員一覧を作成（店舗でフィルタリング）
            List<EmployeeInfo> employees = new ArrayList<>();
            Map<String, List<ShiftInfo>> employeeShifts = Map.of();
            Map<String, Double> employeeMonthlyHours = new HashMap<>();
            
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

                // 従業員別の月次勤務時間（h）を算出
                Map<String, Long> minutesByEmp = monthlyAssignments.stream()
                    .filter(assignment -> finalEmployeeCodes.contains(assignment.employeeCode()))
                    .collect(Collectors.groupingBy(
                        ShiftAssignmentMonthlyView::employeeCode,
                        Collectors.summingLong(assignment -> {
                            long minutes = ChronoUnit.MINUTES.between(assignment.startAt(), assignment.endAt());
                            return Math.max(0, minutes);
                        })
                    ));
                minutesByEmp.forEach((code, minutes) -> employeeMonthlyHours.put(code, minutes / 60.0));
            }

            // 日別人員配置サマリーを取得
            Map<LocalDate, StaffingBalanceService.DailyStaffingSummary> staffingSummaries = 
                staffingBalanceService.getDailyStaffingSummaryForMonth(storeCode, cycleStart, storeCode == null ? null : (departmentCode == null || departmentCode.isEmpty() ? "520" : departmentCode));
            
            // 日別人時サマリーを作成
            int daysInMonth = (int) ChronoUnit.DAYS.between(cycleStart, cycleEnd);
            // 検索フォームと見出しは「ユーザーが選択した月」を表示する
            int year = yearMonth.getYear();
            int month = yearMonth.getMonthValue();
            
            // サイクル期間の日付リストを生成
            List<LocalDate> cycleDateList = Stream
                    .iterate(cycleStart, d -> d.plusDays(1))
                    .limit(daysInMonth)
                    .toList();
            
            Map<Integer, DailyStaffingSummaryInfo> dailyStaffingInfo = new HashMap<>();
            for (int day = 1; day <= daysInMonth; day++) {
                LocalDate date = cycleStart.plusDays(day - 1);
                StaffingBalanceService.DailyStaffingSummary summary = staffingSummaries.get(date);
                
                if (summary != null) {
                    dailyStaffingInfo.put(day, new DailyStaffingSummaryInfo(
                        summary.getTotalRequired(),
                        summary.getTotalAssigned(),
                        summary.getTotalShortageMinutes(),
                        summary.getTotalExcessMinutes()
                    ));
                } else {
                    dailyStaffingInfo.put(day, new DailyStaffingSummaryInfo(0, 0, 0, 0));
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
            model.addAttribute("employeeMonthlyHours", employeeMonthlyHours);
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
            model.addAttribute("employeeMonthlyHours", Map.of());
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
        private final int totalShortageMinutes;
        private final int totalExcessMinutes;
        
        public DailyStaffingSummaryInfo(int totalRequired, int totalAssigned, int totalShortageMinutes, int totalExcessMinutes) {
            this.totalRequired = totalRequired;
            this.totalAssigned = totalAssigned;
            this.totalShortageMinutes = totalShortageMinutes;
            this.totalExcessMinutes = totalExcessMinutes;
        }
        
        public int getTotalRequired() { return totalRequired; }
        public int getTotalAssigned() { return totalAssigned; }
        public int getTotalShortageMinutes() { return totalShortageMinutes; }
        public int getTotalExcessMinutes() { return totalExcessMinutes; }
        public int totalRequired() { return totalRequired; }
        public int totalAssigned() { return totalAssigned; }
        public int totalShortageMinutes() { return totalShortageMinutes; }
        public int totalExcessMinutes() { return totalExcessMinutes; }
    }
}
