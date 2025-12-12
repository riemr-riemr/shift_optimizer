package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.EmployeeRequestMapper;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// DTOクラス
class BatchSaveRequest {
    private List<ChangeRequest> changes;
    private String targetMonth;
    
    public List<ChangeRequest> getChanges() { return changes; }
    public void setChanges(List<ChangeRequest> changes) { this.changes = changes; }
    public String getTargetMonth() { return targetMonth; }
    public void setTargetMonth(String targetMonth) { this.targetMonth = targetMonth; }
}

class ChangeRequest {
    private String employeeCode;
    private String date;
    private String state;
    
    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/employee-requests")
public class EmployeeRequestController {

    private final EmployeeRequestMapper employeeRequestMapper;
    private final EmployeeMapper employeeMapper;
    private final StoreMapper storeMapper;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_REQUEST)")
    public String index(@RequestParam(required = false) String targetMonth, 
                       @RequestParam(required = false) String employeeCode,
                       @RequestParam(required = false) String storeCode,
                       Model model) {
        
        try {
            log.info("Employee request index - targetMonth: {}, employeeCode: {}, storeCode: {}", 
                    targetMonth, employeeCode, storeCode);
            
            YearMonth yearMonth = targetMonth != null ? 
                YearMonth.parse(targetMonth) : YearMonth.now();
            
            // 店舗リストを取得
            List<Store> stores = storeMapper.selectByExample(null);
            stores.sort(Comparator.comparing(Store::getStoreCode));
            log.info("Found {} stores", stores.size());
            
            // 従業員リストを取得（店舗でフィルタリング）
            List<Employee> employees;
            if (storeCode != null && !storeCode.isEmpty()) {
                employees = employeeMapper.selectByStoreCode(storeCode);
                // 念のため上限を適用（大量データ対策）
                if (employees.size() > 200) {
                    employees = employees.stream()
                        .sorted(Comparator.comparing(Employee::getEmployeeCode))
                        .limit(200)
                        .collect(Collectors.toList());
                    log.warn("Employee list truncated to 200 for store: {}", storeCode);
                    model.addAttribute("employeeTruncated", true);
                } else {
                    employees.sort(Comparator.comparing(Employee::getEmployeeCode));
                }
                log.info("Employees for store {}: {}", storeCode, employees.size());
            } else {
                // 店舗が選択されていない場合は従業員を表示しない
                log.info("No store selected - showing empty employee list");
                employees = new ArrayList<>();
            }
        
            // 指定月の希望休データを取得
            LocalDate fromDate = yearMonth.atDay(1);
            LocalDate toDate = yearMonth.atEndOfMonth();
            log.info("Getting requests for date range: {} to {}", fromDate, toDate);
            
            List<EmployeeRequest> requests = employeeRequestMapper.selectByDateRange(fromDate, toDate);
            log.info("Found {} employee requests in date range", requests.size());

            // 表示対象（選択店舗）の従業員コード集合
            final var employeeCodeSet = employees.stream()
                .map(Employee::getEmployeeCode)
                .collect(Collectors.toSet());

            // 従業員別・日別にデータを整理（null日付や対象外従業員を除外）
            Map<String, List<EmployeeRequest>> employeeRequestsMap = requests.stream()
                .filter(r -> r != null)
                .filter(r -> r.getRequestDate() != null)
                .filter(r -> employeeCodeSet.contains(r.getEmployeeCode()))
                .filter(r -> "off".equalsIgnoreCase(r.getRequestKind()))
                .collect(Collectors.groupingBy(EmployeeRequest::getEmployeeCode));
            log.info("Organized requests for {} employees", employeeRequestsMap.size());
            
            // カレンダー表示用のデータを作成（Set形式で高速化）
            Map<String, List<Integer>> employeeDayOffMap = new HashMap<>();
            for (Map.Entry<String, List<EmployeeRequest>> entry : employeeRequestsMap.entrySet()) {
                List<Integer> dayOffList = entry.getValue().stream()
                    .map(request -> toLocalDate(request.getRequestDate()).getDayOfMonth())
                    .sorted()
                    .collect(Collectors.toList());
                employeeDayOffMap.put(entry.getKey(), dayOffList);
            }

            // 基本属性をモデルに追加
            model.addAttribute("year", yearMonth.getYear());
            model.addAttribute("month", yearMonth.getMonthValue());
            model.addAttribute("daysInMonth", yearMonth.lengthOfMonth());
            model.addAttribute("stores", stores);
            model.addAttribute("employees", employees);
            model.addAttribute("employeeDayOffMap", employeeDayOffMap);
            model.addAttribute("selectedEmployeeCode", employeeCode);
            model.addAttribute("selectedStoreCode", storeCode);
            
            log.info("Rendering template with {} employees, {} days in month", 
                    employees.size(), yearMonth.lengthOfMonth());
            
            return "employee-request/index";
            
        } catch (Exception e) {
            log.error("Error in employee request index", e);
            model.addAttribute("error", "データの取得中にエラーが発生しました: " + e.getMessage());
            return "employee-request/error";
        }
    }

    @PostMapping("/toggle")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_REQUEST)")
    @ResponseBody
    public String toggleDayOff(@RequestParam String employeeCode,
                              @RequestParam String date) {
        try {
            log.info("toggleDayOff called with employeeCode: {}, date: {}", employeeCode, date);
            
            LocalDate requestDate = LocalDate.parse(date);
            log.info("Parsed date: {}", requestDate);
            
            // 既存の希望休をチェック
            EmployeeRequest existingRequest = employeeRequestMapper.selectByEmployeeAndDate(
                employeeCode, requestDate);
            log.info("Existing request: {}", existingRequest != null ? "found" : "not found");
            
            if (existingRequest != null) {
                // 既存の希望休を削除
                log.info("Deleting existing request with ID: {}", existingRequest.getRequestId());
                employeeRequestMapper.deleteById(existingRequest.getRequestId());
                return "removed";
            } else {
                // 新しい希望休を追加
                log.info("Creating new request for employee: {}", employeeCode);
                
                // storeCode を従業員マスタから補完（NOT NULL 制約対策）
                Employee emp = employeeMapper.selectByPrimaryKey(employeeCode);
                log.info("Employee lookup result: {}", emp != null ? "found" : "not found");
                
                if (emp != null && emp.getStoreCode() != null) {
                    log.info("Employee storeCode: {}", emp.getStoreCode());
                    
                    EmployeeRequest newRequest = new EmployeeRequest();
                    newRequest.setEmployeeCode(employeeCode);
                    newRequest.setRequestDate(toDate(requestDate));
                    newRequest.setRequestKind("off");
                    newRequest.setNote("希望休");
                    newRequest.setStoreCode(emp.getStoreCode());
                    newRequest.setPriority(2); // デフォルト優先度を設定（NOT NULL制約対策）
                    
                    log.info("Inserting new request: employeeCode={}, storeCode={}, date={}, kind={}", 
                            newRequest.getEmployeeCode(), newRequest.getStoreCode(), 
                            newRequest.getRequestDate(), newRequest.getRequestKind());
                    
                    employeeRequestMapper.insert(newRequest);
                    log.info("Successfully inserted new request");
                    return "added";
                } else {
                    log.error("Employee not found or storeCode is null for employeeCode: {}", employeeCode);
                    return "error: 従業員情報が見つからないか、店舗情報が不正です";
                }
            }
        } catch (Exception e) {
            log.error("Error in toggleDayOff: ", e);
            return "error: " + e.getMessage();
        }
    }

    @PostMapping("/batch-save")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_REQUEST)")
    @ResponseBody
    public String batchSave(@RequestBody BatchSaveRequest request) {
        try {
            log.info("Batch save request: {} changes", request.getChanges().size());
            
            for (ChangeRequest change : request.getChanges()) {
                String employeeCode = change.getEmployeeCode();
                String date = change.getDate();
                String state = change.getState();
                
                log.info("Processing change: employee={}, date={}, state={}", employeeCode, date, state);
                
                LocalDate requestDate = LocalDate.parse(date);
                
                // 既存の希望休をチェック
                EmployeeRequest existingRequest = employeeRequestMapper.selectByEmployeeAndDate(
                    employeeCode, requestDate);
                
                // state: 'on' = 希望休あり, 'off' = 希望休なし
                if ("on".equals(state)) {
                    // 希望休を追加
                    if (existingRequest == null) {
                        Employee emp = employeeMapper.selectByPrimaryKey(employeeCode);
                        if (emp != null && emp.getStoreCode() != null) {
                            EmployeeRequest newRequest = new EmployeeRequest();
                            newRequest.setEmployeeCode(employeeCode);
                            newRequest.setRequestDate(toDate(requestDate));
                            newRequest.setRequestKind("off");
                            newRequest.setNote("希望休");
                            newRequest.setStoreCode(emp.getStoreCode());
                            newRequest.setPriority(2);
                            
                            employeeRequestMapper.insert(newRequest);
                        }
                    }
                } else if ("off".equals(state)) {
                    // 希望休を削除
                    if (existingRequest != null) {
                        employeeRequestMapper.deleteById(existingRequest.getRequestId());
                    }
                }
            }
            
            log.info("Batch save completed successfully");
            return "success";
            
        } catch (Exception e) {
            log.error("Error in batch save", e);
            return "error: " + e.getMessage();
        }
    }
    
    @PostMapping("/bulk-save")
    @PreAuthorize("@screenAuth.hasUpdatePermission(T(io.github.riemr.shift.util.ScreenCodes).EMPLOYEE_REQUEST)")
    public String bulkSave(@RequestParam String employeeCode,
                          @RequestParam String targetMonth,
                          @RequestParam(required = false) List<String> selectedDates,
                          RedirectAttributes redirectAttributes) {
        try {
            YearMonth yearMonth = YearMonth.parse(targetMonth);
            LocalDate fromDate = yearMonth.atDay(1);
            LocalDate toDate = yearMonth.atEndOfMonth();
            
            // 既存の希望休を削除
            List<EmployeeRequest> existingRequests = employeeRequestMapper
                .selectByEmployeeAndDateRange(employeeCode, fromDate, toDate);
            
            for (EmployeeRequest request : existingRequests) {
                if ("off".equalsIgnoreCase(request.getRequestKind())) {
                    employeeRequestMapper.deleteById(request.getRequestId());
                }
            }
            
            // 新しい希望休を追加
            if (selectedDates != null && !selectedDates.isEmpty()) {
                for (String dateStr : selectedDates) {
                    LocalDate requestDate = LocalDate.parse(dateStr);
                    
                    EmployeeRequest newRequest = new EmployeeRequest();
                    newRequest.setEmployeeCode(employeeCode);
                    newRequest.setRequestDate(toDate(requestDate));
                    newRequest.setRequestKind("off");
                    newRequest.setNote("希望休");
                    newRequest.setPriority(2); // デフォルト優先度を設定（NOT NULL制約対策）
                    
                    // storeCode を従業員マスタから補完
                    Employee empForInsert = employeeMapper.selectByPrimaryKey(employeeCode);
                    if (empForInsert != null && empForInsert.getStoreCode() != null) {
                        newRequest.setStoreCode(empForInsert.getStoreCode());
                    } else {
                        log.error("Employee not found or storeCode is null for employeeCode: {} in bulkSave", employeeCode);
                        redirectAttributes.addFlashAttribute("error", 
                            "従業員情報が見つからないか、店舗情報が不正です: " + employeeCode);
                        return "redirect:/employee-requests?targetMonth=" + targetMonth;
                    }
                    
                    employeeRequestMapper.insert(newRequest);
                }
            }
            
            redirectAttributes.addFlashAttribute("success", 
                "従業員 " + employeeCode + " の希望休を保存しました。");
            
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", 
                "保存中にエラーが発生しました: " + e.getMessage());
        }
        
        // 保存後も店舗選択が維持されるよう storeCode を付与
        Employee redirectEmp = employeeMapper.selectByPrimaryKey(employeeCode);
        String redirectStoreCode = redirectEmp != null ? redirectEmp.getStoreCode() : null;
        return "redirect:/employee-requests?targetMonth=" + targetMonth + 
               "&employeeCode=" + employeeCode +
               (redirectStoreCode != null && !redirectStoreCode.isEmpty() ? "&storeCode=" + redirectStoreCode : "");
    }
    
    @GetMapping("/simple")
    public String simple(@RequestParam(required = false) String targetMonth, 
                         @RequestParam(required = false) String storeCode,
                         Model model) {
        
        try {
            log.info("Simple endpoint - targetMonth: {}, storeCode: {}", targetMonth, storeCode);
            
            YearMonth yearMonth = targetMonth != null ? 
                YearMonth.parse(targetMonth) : YearMonth.now();
            
            // 最小限のデータのみ取得
            List<Employee> allEmployees = employeeMapper.selectAll();
            log.info("Total employees from DB: {}", allEmployees.size());
            
            List<Employee> employees = new ArrayList<>();
            if (storeCode != null && !storeCode.isEmpty()) {
                for (Employee emp : allEmployees) {
                    if (storeCode.equals(emp.getStoreCode()) && employees.size() < 5) {
                        employees.add(emp);
                    }
                }
                log.info("Found {} employees for store {}", employees.size(), storeCode);
            }

            model.addAttribute("year", yearMonth.getYear());
            model.addAttribute("month", yearMonth.getMonthValue());
            model.addAttribute("daysInMonth", yearMonth.lengthOfMonth());
            model.addAttribute("employees", employees);
            model.addAttribute("selectedStoreCode", storeCode);
            model.addAttribute("totalEmployees", allEmployees.size());
            
            return "employee-request/simple";
            
        } catch (Exception e) {
            log.error("Error in simple endpoint", e);
            model.addAttribute("error", "エラー: " + e.getMessage());
            return "employee-request/error";
        }
    }
    
    // LocalDate to Date 変換ヘルパー
    private Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
    
    // Date to LocalDate 変換ヘルパー
    private LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
