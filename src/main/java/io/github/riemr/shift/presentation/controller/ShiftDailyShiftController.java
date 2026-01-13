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
import io.github.riemr.shift.application.service.TaskCategoryMasterService;
import io.github.riemr.shift.application.service.TaskMasterService;
import io.github.riemr.shift.optimization.entity.RegisterDemandSlot;
import io.github.riemr.shift.optimization.entity.WorkDemandSlot;
import io.github.riemr.shift.infrastructure.mapper.RegisterDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.mapper.WorkDemandIntervalMapper;
import io.github.riemr.shift.infrastructure.mapper.RegisterAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.DepartmentTaskAssignmentMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeRequestMapper;
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
import io.github.riemr.shift.application.dto.ShiftAttendanceSaveRequest;
import io.github.riemr.shift.application.dto.EmployeeRequestDeleteRequest;
import io.github.riemr.shift.application.service.AppSettingService;
import io.github.riemr.shift.application.dto.StaffingBalanceDto;
import io.github.riemr.shift.application.dto.ScorePoint;
import io.github.riemr.shift.application.dto.DailySolveRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.DepartmentTaskAssignment;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskCategoryMaster;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskMaster;
import io.github.riemr.shift.util.OffRequestKinds;
import io.github.riemr.shift.util.EmployeeRequestKinds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.temporal.ChronoUnit;

@Controller
@RequiredArgsConstructor
@RequestMapping("/shift")
@Slf4j
public class ShiftDailyShiftController {

    private final ShiftScheduleService service;
    private final StaffingBalanceService staffingBalanceService;
    private final ShiftOptimizationPreparationService preparationService;
    private final StoreMapper storeMapper;
    private final RegisterDemandHourService registerDemandHourService;
    private final TaskCategoryMasterService taskCategoryMasterService;
    private final TaskMasterService taskMasterService;
    private final RegisterDemandIntervalMapper registerDemandIntervalMapper;
    private final WorkDemandIntervalMapper workDemandIntervalMapper;
    private final RegisterAssignmentMapper registerAssignmentMapper;
    private final DepartmentTaskAssignmentMapper departmentTaskAssignmentMapper;
    private final ShiftAssignmentMapper shiftAssignmentMapper;
    private final EmployeeRequestMapper employeeRequestMapper;
    private final EmployeeMapper employeeMapper;
    private final EmployeeDepartmentMapper employeeDepartmentMapper;
    private final StoreDepartmentMapper storeDepartmentMapper;
    private final AppSettingService appSettingService;

    @GetMapping("/daily-shift")
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_DAILY)")
    public String view(Model model) {
        List<Store> stores = storeMapper.selectByExample(null);
        stores.sort(Comparator.comparing(Store::getStoreCode));
        model.addAttribute("stores", stores);
        model.addAttribute("timeResolutionMinutes", appSettingService.getTimeResolutionMinutes());
        return "shift/daily-shift";
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

            var tasks = departmentTaskAssignmentMapper.selectByDate(storeCode, "520", from, to);
            for (var t : tasks) {
                String code = t.getEmployeeCode();
                String name = (code == null) ? null : empName.getOrDefault(code, code);
                results.add(new ShiftAssignmentView(
                        t.getStartAt().toInstant().atZone(zone).toLocalDateTime().toString(),
                        t.getEndAt().toInstant().atZone(zone).toLocalDateTime().toString(),
                        null,
                        "520",
                        "DEPARTMENT_TASK",
                        t.getTaskCode(),
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
                    name,
                    "manual_edit".equalsIgnoreCase(s.getCreatedBy())
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

    @GetMapping("/api/calc/task-masters")
    @ResponseBody
    public List<TaskMaster> getTaskMasters(@RequestParam(value = "departmentCode", required = false) String departmentCode) {
        var list = taskMasterService.list();
        if (departmentCode == null || departmentCode.isBlank()) {
            return list;
        }
        return list.stream()
                .filter(t -> departmentCode.equals(t.getDepartmentCode()))
                .toList();
    }

    @GetMapping("/api/calc/task-categories")
    @ResponseBody
    public List<TaskCategoryMaster> getTaskCategories() {
        return taskCategoryMasterService.list();
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

    @GetMapping("/api/calc/department-task-slot/{date}")
    @ResponseBody
    public List<WorkDemandSlot> getDepartmentTaskSlotsByDate(@PathVariable("date") String dateString,
                                                             @RequestParam("storeCode") String storeCode,
                                                             @RequestParam(value = "departmentCode", required = false) String departmentCode) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        List<String> departments;
        if (departmentCode == null || departmentCode.isBlank()) {
            departments = storeDepartmentMapper.findDepartmentsByStore(storeCode).stream()
                    .map(DepartmentMaster::getDepartmentCode)
                    .toList();
        } else {
            departments = List.of(departmentCode);
        }
        int resMin = appSettingService.getTimeResolutionMinutes();
        Map<String, WorkDemandSlot> byTaskTime = new HashMap<>();
        for (String dept : departments) {
            List<DepartmentTaskAssignment> tasks = departmentTaskAssignmentMapper.selectByDate(storeCode, dept, date, date.plusDays(1));
            for (DepartmentTaskAssignment task : tasks) {
                if ("BREAK".equals(task.getTaskCode())) continue;
                LocalDateTime start = task.getStartAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime end = task.getEndAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime t = start;
                while (t.isBefore(end)) {
                    String taskCode = task.getTaskCode() == null ? "TASK" : task.getTaskCode();
                    String key = dept + "_" + taskCode + "_" + t.toLocalTime();
                    WorkDemandSlot slot = byTaskTime.get(key);
                    if (slot == null) {
                        slot = new WorkDemandSlot();
                        slot.setStoreCode(storeCode);
                        slot.setDepartmentCode(dept);
                        slot.setDemandDate(date);
                        slot.setSlotTime(t.toLocalTime());
                        slot.setTaskCode(task.getTaskCode());
                        slot.setRequiredUnits(0);
                        byTaskTime.put(key, slot);
                    }
                    slot.setRequiredUnits(slot.getRequiredUnits() + 1);
                    t = t.plusMinutes(resMin);
                }
            }
        }
        List<WorkDemandSlot> result = new ArrayList<>(byTaskTime.values());
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

    @PostMapping("/api/calc/shifts/save")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public Map<String, Object> saveShiftAttendance(@RequestBody ShiftAttendanceSaveRequest request) {
        try {
            service.saveShiftAttendanceChange(request);
            return Map.of("success", true, "message", "変更が保存されました");
        } catch (Exception e) {
            return Map.of("success", false, "message", "保存中にエラーが発生しました: " + e.getMessage());
        }
    }

    @PostMapping("/api/employee-request/delete")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public Map<String, Object> deleteEmployeeRequest(@RequestBody EmployeeRequestDeleteRequest request) {
        try {
            int deleted = service.deleteEmployeeRequestForDate(request.storeCode(), request.employeeCode(), request.date());
            return Map.of("success", true, "deleted", deleted);
        } catch (Exception e) {
            return Map.of("success", false, "message", "削除中にエラーが発生しました: " + e.getMessage());
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

    @PostMapping("/api/clear/attendance-manual")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).SHIFT_MONTHLY)")
    @ResponseBody
    public Map<String, Object> clearManualAttendance(@RequestBody SolveRequest req) {
        try {
            LocalDate base = LocalDate.parse(req.month() + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            int startDay = appSettingService.getShiftCycleStartDay();
            LocalDate cycleStart = computeCycleStart(base, startDay);
            Map<String, Integer> deleted = service.clearManualAttendance(cycleStart, req.storeCode(), req.departmentCode());
            return Map.of("success", true, "deleted", deleted, "message", "手入力を削除しました");
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
            List<DepartmentMaster> departments = List.of();
            if (storeCode != null && !storeCode.isBlank()) {
                departments = storeDepartmentMapper.findDepartmentsByStore(storeCode);
                departments.sort(Comparator.comparing(DepartmentMaster::getDepartmentCode));
            }
            
            // 月次シフトデータ取得（出勤＝shift_assignmentベース）
            List<ShiftAssignmentMonthlyView> monthlyAssignments = service.fetchShiftsByMonth(cycleStart, storeCode, departmentCode);

            // 従業員一覧を作成（店舗・部門でフィルタリング）
            List<EmployeeInfo> employees = new ArrayList<>();
            Map<String, List<ShiftInfo>> employeeShifts = Map.of();
            Map<String, Double> employeeMonthlyHours = new HashMap<>();
            Map<String, String> employeeOffKinds = new HashMap<>();
            Map<String, String> employeePreferOnTimes = new HashMap<>();
            Map<String, Integer> employeeWorkDays = new HashMap<>();
            Map<String, Integer> employeeOffDays = new HashMap<>();
            Map<LocalDate, Long> assignedMinutesByDate = new HashMap<>();
            Map<LocalDate, Set<String>> headcountByDate = new HashMap<>();

            if (storeCode != null && !storeCode.isEmpty()) {
                List<Employee> storeEmployees = employeeMapper.selectByStoreCode(storeCode);
                if (departmentCode != null && !departmentCode.isBlank() && !"520".equalsIgnoreCase(departmentCode)) {
                    Set<String> allowed = employeeDepartmentMapper.selectByDepartment(departmentCode).stream()
                            .map(ed -> ed.getEmployeeCode())
                            .filter(code -> code != null && !code.isBlank())
                            .collect(Collectors.toSet());
                    storeEmployees = storeEmployees.stream()
                            .filter(emp -> allowed.contains(emp.getEmployeeCode()))
                            .toList();
                }
                employees = storeEmployees.stream()
                        .map(emp -> new EmployeeInfo(emp.getEmployeeCode(), emp.getEmployeeName()))
                        .sorted(Comparator.comparing(EmployeeInfo::employeeCode))
                        .toList();
            }

            final Set<String> finalEmployeeCodes = employees.stream()
                    .map(EmployeeInfo::employeeCode)
                    .collect(Collectors.toSet());

            if (monthlyAssignments != null && !monthlyAssignments.isEmpty() && !finalEmployeeCodes.isEmpty()) {
                employeeShifts = monthlyAssignments.stream()
                        .filter(assignment -> finalEmployeeCodes.contains(assignment.employeeCode()))
                        .collect(Collectors.groupingBy(
                                assignment -> assignment.employeeCode() + "_" + assignment.date().toString(),
                                Collectors.mapping(assignment -> new ShiftInfo(
                                        assignment.startAt().format(DateTimeFormatter.ofPattern("HH:mm")),
                                        assignment.endAt().format(DateTimeFormatter.ofPattern("HH:mm")),
                                        assignment.manualEdit()
                                ), Collectors.toList())
                        ));

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

                Map<String, Set<LocalDate>> workDatesByEmp = monthlyAssignments.stream()
                        .filter(assignment -> finalEmployeeCodes.contains(assignment.employeeCode()))
                        .collect(Collectors.groupingBy(
                                ShiftAssignmentMonthlyView::employeeCode,
                                Collectors.mapping(ShiftAssignmentMonthlyView::date, Collectors.toSet())
                        ));
                workDatesByEmp.forEach((code, dates) -> employeeWorkDays.put(code, dates.size()));

                monthlyAssignments.stream()
                        .filter(assignment -> finalEmployeeCodes.contains(assignment.employeeCode()))
                        .forEach(assignment -> {
                            long minutes = ChronoUnit.MINUTES.between(assignment.startAt(), assignment.endAt());
                            LocalDate date = assignment.date();
                            assignedMinutesByDate.merge(date, Math.max(0, minutes), Long::sum);
                            headcountByDate.computeIfAbsent(date, k -> new HashSet<>()).add(assignment.employeeCode());
                        });
            }

            if (storeCode != null && !storeCode.isEmpty() && !employees.isEmpty()) {
                LocalDate cycleEndForOff = cycleStart.plusMonths(1);
                Set<String> employeeCodesForOff = employees.stream()
                        .map(EmployeeInfo::employeeCode)
                        .collect(Collectors.toSet());
                employeeRequestMapper.selectByDateRange(cycleStart, cycleEndForOff).stream()
                        .filter(req -> req != null && req.getRequestDate() != null)
                        .filter(req -> storeCode.equals(req.getStoreCode()))
                        .filter(req -> employeeCodesForOff.contains(req.getEmployeeCode()))
                        .forEach(req -> {
                            String kind = req.getRequestKind() == null ? "" : req.getRequestKind().trim();
                            if (OffRequestKinds.normalize(kind) != null) {
                                LocalDate d = req.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                                String key = req.getEmployeeCode() + "_" + d.toString();
                                employeeOffKinds.put(key, OffRequestKinds.normalize(kind));
                                return;
                            }
                            if (EmployeeRequestKinds.PREFER_ON.equalsIgnoreCase(kind)) {
                                LocalDate d = req.getRequestDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                                LocalTime from = toLocalTimeSafe(req.getFromTime());
                                LocalTime to = toLocalTimeSafe(req.getToTime());
                                if (from == null || to == null) return;
                                String key = req.getEmployeeCode() + "_" + d.toString();
                                employeePreferOnTimes.put(key, from.format(DateTimeFormatter.ofPattern("HH:mm"))
                                        + "|" + to.format(DateTimeFormatter.ofPattern("HH:mm")));
                            }
                        });
            }

            // 日別人員配置サマリーを取得
            Map<LocalDate, StaffingBalanceService.DailyStaffingSummary> staffingSummaries = 
                staffingBalanceService.getDailyStaffingSummaryForMonth(storeCode, cycleStart, storeCode == null ? null : (departmentCode == null || departmentCode.isEmpty() ? "520" : departmentCode));
            
            // 日別人時サマリーを作成
            int daysInMonth = (int) ChronoUnit.DAYS.between(cycleStart, cycleEnd);
            int resolutionMinutes = appSettingService.getTimeResolutionMinutes();
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
                long assignedMinutes = assignedMinutesByDate.getOrDefault(date, 0L);
                int headcount = headcountByDate.getOrDefault(date, Set.of()).size();
                
                if (summary != null) {
                    int requiredMinutes = Math.max(0, summary.getTotalRequired()) * resolutionMinutes;
                    dailyStaffingInfo.put(day, new DailyStaffingSummaryInfo(
                        summary.getTotalRequired(),
                        summary.getTotalAssigned(),
                        summary.getTotalShortageMinutes(),
                        summary.getTotalExcessMinutes(),
                        requiredMinutes,
                        assignedMinutes,
                        headcount
                    ));
                } else {
                    dailyStaffingInfo.put(day, new DailyStaffingSummaryInfo(0, 0, 0, 0, 0, assignedMinutes, headcount));
                }
            }

            if (!employees.isEmpty()) {
                int totalDays = cycleDateList.size();
                employees.forEach(emp -> {
                    int workDays = employeeWorkDays.getOrDefault(emp.employeeCode(), 0);
                    employeeOffDays.put(emp.employeeCode(), Math.max(0, totalDays - workDays));
                });
            }

            model.addAttribute("year", year);
            model.addAttribute("month", month);
            model.addAttribute("daysInMonth", daysInMonth);
            model.addAttribute("stores", stores);
            model.addAttribute("departments", departments);
            model.addAttribute("dateList", cycleDateList);
            model.addAttribute("selectedStoreCode", storeCode);
            model.addAttribute("selectedDepartmentCode", departmentCode);
            model.addAttribute("employees", employees);
            model.addAttribute("employeeShifts", employeeShifts);
            model.addAttribute("employeeMonthlyHours", employeeMonthlyHours);
            model.addAttribute("employeeOffKinds", employeeOffKinds);
            model.addAttribute("employeePreferOnTimes", employeePreferOnTimes);
            model.addAttribute("employeeWorkDays", employeeWorkDays);
            model.addAttribute("employeeOffDays", employeeOffDays);
            model.addAttribute("dailyStaffingInfo", dailyStaffingInfo);
            
            return "shift/monthly-shift";
        } catch (Exception e) {
            // エラーハンドリング
            YearMonth currentMonth = YearMonth.now();
            List<Store> stores = storeMapper.selectByExample(null);
            stores.sort(Comparator.comparing(Store::getStoreCode));
            List<DepartmentMaster> departments = List.of();
            if (storeCode != null && !storeCode.isBlank()) {
                departments = storeDepartmentMapper.findDepartmentsByStore(storeCode);
                departments.sort(Comparator.comparing(DepartmentMaster::getDepartmentCode));
            }
            
            model.addAttribute("year", currentMonth.getYear());
            model.addAttribute("month", currentMonth.getMonthValue());
            model.addAttribute("daysInMonth", currentMonth.lengthOfMonth());
            model.addAttribute("stores", stores);
            model.addAttribute("departments", departments);
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
        private final boolean manualEdit;
        
        public ShiftInfo(String startTime, String endTime, boolean manualEdit) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.manualEdit = manualEdit;
        }
        
        public String getStartTime() { return startTime; }
        public String getEndTime() { return endTime; }
        public boolean getManualEdit() { return manualEdit; }
        public String startTime() { return startTime; }
        public String endTime() { return endTime; }
        public boolean manualEdit() { return manualEdit; }
    }
    
    // 内部クラスで日別人員配置サマリーを定義
    public static class DailyStaffingSummaryInfo {
        private final int totalRequired;
        private final int totalAssigned;
        private final int totalShortageMinutes;
        private final int totalExcessMinutes;
        private final int requiredMinutes;
        private final long assignedMinutes;
        private final int headcount;
        
        public DailyStaffingSummaryInfo(int totalRequired, int totalAssigned, int totalShortageMinutes, int totalExcessMinutes,
                                        int requiredMinutes, long assignedMinutes, int headcount) {
            this.totalRequired = totalRequired;
            this.totalAssigned = totalAssigned;
            this.totalShortageMinutes = totalShortageMinutes;
            this.totalExcessMinutes = totalExcessMinutes;
            this.requiredMinutes = requiredMinutes;
            this.assignedMinutes = assignedMinutes;
            this.headcount = headcount;
        }
        
        public int getTotalRequired() { return totalRequired; }
        public int getTotalAssigned() { return totalAssigned; }
        public int getTotalShortageMinutes() { return totalShortageMinutes; }
        public int getTotalExcessMinutes() { return totalExcessMinutes; }
        public int getRequiredMinutes() { return requiredMinutes; }
        public long getAssignedMinutes() { return assignedMinutes; }
        public int getHeadcount() { return headcount; }
        public int totalRequired() { return totalRequired; }
        public int totalAssigned() { return totalAssigned; }
        public int totalShortageMinutes() { return totalShortageMinutes; }
        public int totalExcessMinutes() { return totalExcessMinutes; }
        public int requiredMinutes() { return requiredMinutes; }
        public long assignedMinutes() { return assignedMinutes; }
        public int headcount() { return headcount; }
    }

    private LocalTime toLocalTimeSafe(java.util.Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Time) return ((java.sql.Time) date).toLocalTime();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
    }
}
