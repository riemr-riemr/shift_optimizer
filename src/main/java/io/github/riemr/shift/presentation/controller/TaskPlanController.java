package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.repository.TaskPlanRepository;
import io.github.riemr.shift.application.service.TaskPlanService;
import io.github.riemr.shift.application.repository.DaysMasterRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.DaysMaster;
import io.github.riemr.shift.application.service.DepartmentSkillMatrixService;
import io.github.riemr.shift.application.service.TaskMasterService;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.Store;
import io.github.riemr.shift.infrastructure.mapper.TaskCategoryMasterMapper;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskCategoryMaster;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.beans.propertyeditors.CustomDateEditor;

import java.time.LocalDate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;

@Controller
@RequestMapping("/tasks")
public class TaskPlanController {
    private final TaskPlanRepository planRepository;
    private final TaskPlanService planService;
    private final TaskMasterService taskMasterService;
    private final DaysMasterRepository daysMasterRepository;
    private final DepartmentSkillMatrixService departmentSkillMatrixService;
    private final StoreMapper storeMapper;
    private final TaskCategoryMasterMapper taskCategoryMasterMapper;

    public TaskPlanController(TaskPlanRepository planRepository,
                              TaskPlanService planService,
                              TaskMasterService taskMasterService,
                              DaysMasterRepository daysMasterRepository,
                              DepartmentSkillMatrixService departmentSkillMatrixService,
                              StoreMapper storeMapper,
                              TaskCategoryMasterMapper taskCategoryMasterMapper) {
        this.planRepository = planRepository;
        this.planService = planService;
        this.taskMasterService = taskMasterService;
        this.daysMasterRepository = daysMasterRepository;
        this.departmentSkillMatrixService = departmentSkillMatrixService;
        this.storeMapper = storeMapper;
        this.taskCategoryMasterMapper = taskCategoryMasterMapper;
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        // Accept HH:mm for time-only fields and yyyy-MM-dd for date fields
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm");
        timeFmt.setLenient(false);
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        dateFmt.setLenient(false);
        binder.registerCustomEditor(Date.class, "fixedStartTime", new CustomDateEditor(timeFmt, true));
        binder.registerCustomEditor(Date.class, "fixedEndTime", new CustomDateEditor(timeFmt, true));
        binder.registerCustomEditor(Date.class, "windowStartTime", new CustomDateEditor(timeFmt, true));
        binder.registerCustomEditor(Date.class, "windowEndTime", new CustomDateEditor(timeFmt, true));
        binder.registerCustomEditor(Date.class, "specialDate", new CustomDateEditor(dateFmt, true));
        binder.registerCustomEditor(Date.class, "effectiveFrom", new CustomDateEditor(dateFmt, true));
        binder.registerCustomEditor(Date.class, "effectiveTo", new CustomDateEditor(dateFmt, true));
    }

    @GetMapping("/plan")
    public String planIndex(@RequestParam(name = "store", required = false) String storeCode,
                            @RequestParam(name = "mode", required = false, defaultValue = "weekly") String mode,
                            @RequestParam(name = "day", required = false) Short dayOfWeek,
                            @RequestParam(name = "sd", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate selectedDate,
                            @RequestParam(name = "dept", required = false) String departmentCode,
                            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            Model model) {
        model.addAttribute("storeCode", storeCode);
        model.addAttribute("mode", mode);
        model.addAttribute("day", dayOfWeek);
        model.addAttribute("sd", selectedDate);
        model.addAttribute("dept", departmentCode);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("departments", departmentSkillMatrixService.listDepartments());
        
        // 店舗リストを追加
        model.addAttribute("stores", storeMapper.selectByExample(null));
        
        // カテゴリリストを追加
        model.addAttribute("categories", taskCategoryMasterMapper.selectAll());
        if (storeCode != null) {
            // Always provide special days list for copy modal
            model.addAttribute("days", daysMasterRepository.listSpecialByStore(storeCode));
            List<?> results = null;
            if ("weekly".equalsIgnoreCase(mode)) {
                short dow = dayOfWeek == null ? 1 : dayOfWeek;
                if (departmentCode != null && !departmentCode.isBlank()) {
                    results = planRepository.listWeeklyByStoreAndDowAndDept(storeCode, dow, departmentCode);
                } else {
                    results = planRepository.listWeeklyByStoreAndDow(storeCode, dow);
                }
                model.addAttribute("list", results);
            } else {
                // tabs: special dates list from days_master (already set above as well)
                if (selectedDate != null) {
                    Date specDate = Date.from(selectedDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
                    if (departmentCode != null && !departmentCode.isBlank()) {
                        results = planRepository.selectSpecialByStoreAndDateAndDept(storeCode, specDate, departmentCode);
                    } else {
                        results = planRepository.selectSpecialByStoreAndDate(storeCode, specDate);
                    }
                    model.addAttribute("list", results);
                } else {
                    model.addAttribute("list", java.util.Collections.emptyList());
                }
            }
        } else {
            model.addAttribute("list", java.util.Collections.emptyList());
        }
        model.addAttribute("masters", taskMasterService.list());
        model.addAttribute("form", new TaskPlan());
        
        // グリッド表示用の作業計画データを追加（JSで扱いやすいMapへシリアライズ）
        if (storeCode != null) {
            List<?> gridResults = null;
            if ("weekly".equalsIgnoreCase(mode)) {
                short dow = dayOfWeek == null ? 1 : dayOfWeek;
                gridResults = (departmentCode != null && !departmentCode.isBlank())
                        ? planRepository.listWeeklyByStoreAndDowAndDept(storeCode, dow, departmentCode)
                        : planRepository.listWeeklyByStoreAndDow(storeCode, dow);
            } else {
                if (selectedDate != null) {
                    Date specDate = Date.from(selectedDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
                    gridResults = (departmentCode != null && !departmentCode.isBlank())
                            ? planRepository.selectSpecialByStoreAndDateAndDept(storeCode, specDate, departmentCode)
                            : planRepository.selectSpecialByStoreAndDate(storeCode, specDate);
                } else {
                    gridResults = java.util.Collections.emptyList();
                }
            }
            // Serialize to list of maps
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm");
            List<java.util.Map<String,Object>> serialized = new java.util.ArrayList<>();
            for (Object o : gridResults) {
                if (o instanceof TaskPlan p) {
                    java.util.Map<String,Object> m = new java.util.HashMap<>();
                    m.put("planId", p.getPlanId());
                    m.put("taskCode", p.getTaskCode());
                    m.put("departmentCode", p.getDepartmentCode());
                    m.put("requiredStaffCount", p.getRequiredStaffCount());
                    m.put("lane", p.getLane());
                    m.put("scheduleType", p.getScheduleType());
                    if (p.getFixedStartTime() != null) m.put("fixedStartTime", hm.format(p.getFixedStartTime()));
                    if (p.getFixedEndTime() != null) m.put("fixedEndTime", hm.format(p.getFixedEndTime()));
                    if (p.getWindowStartTime() != null) m.put("windowStartTime", hm.format(p.getWindowStartTime()));
                    if (p.getWindowEndTime() != null) m.put("windowEndTime", hm.format(p.getWindowEndTime()));
                    if (p.getRequiredDurationMinutes() != null) m.put("requiredDurationMinutes", p.getRequiredDurationMinutes());
                    serialized.add(m);
                }
            }
            model.addAttribute("gridPlans", serialized);
        } else {
            model.addAttribute("gridPlans", java.util.Collections.emptyList());
        }
        
        return "tasks/plan/index";
    }

    @PostMapping("/plan/days")
    public String addSpecialDay(@RequestParam("store") String storeCode,
                                @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate specialDate,
                                @RequestParam("label") String label) {
        DaysMaster d = new DaysMaster();
        d.setStoreCode(storeCode);
        d.setKind("SPECIAL");
        d.setSpecialDate(java.util.Date.from(specialDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
        d.setLabel(label);
        d.setActive(true);
        daysMasterRepository.save(d);
        return "redirect:/tasks/plan?store=" + storeCode + "&mode=special&sd=" + specialDate;
    }

    @PostMapping(value = "/plan")
    public Object create(@RequestParam("mode") String mode,
                         @ModelAttribute("form") TaskPlan form,
                         jakarta.servlet.http.HttpServletRequest request) {
        // Set safe defaults for quick grid posts
        if (form.getActive() == null) form.setActive(Boolean.TRUE);
        if (form.getRequiredStaffCount() == null) form.setRequiredStaffCount(1);
        if ("weekly".equalsIgnoreCase(mode)) {
            form.setPlanKind("WEEKLY");
            planRepository.save(form);
            String redirect = "redirect:/tasks/plan?store=" + form.getStoreCode() + "&mode=weekly&day=" + (form.getDayOfWeek() == null ? 1 : form.getDayOfWeek());
            if (form.getDepartmentCode() != null && !form.getDepartmentCode().isBlank()) {
                redirect += "&dept=" + form.getDepartmentCode();
            }
            if (isAjax(request)) {
                return ResponseEntity.ok(java.util.Map.of("id", form.getPlanId()));
            }
            return redirect;
        } else {
            form.setPlanKind("SPECIAL");
            planRepository.save(form);
            LocalDate d = form.getSpecialDate().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            String redirect = "redirect:/tasks/plan?store=" + form.getStoreCode() + "&mode=special&sd=" + d;
            if (form.getDepartmentCode() != null && !form.getDepartmentCode().isBlank()) {
                redirect += "&dept=" + form.getDepartmentCode();
            }
            if (isAjax(request)) {
                return ResponseEntity.ok(java.util.Map.of("id", form.getPlanId()));
            }
            return redirect;
        }
    }

    private boolean isAjax(jakarta.servlet.http.HttpServletRequest request) {
        String xrw = request.getHeader("X-Requested-With");
        String accept = request.getHeader("Accept");
        return (xrw != null && !xrw.isBlank()) || (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE));
    }

    @PostMapping("/plan/{id}/update")
    public String update(@PathVariable("id") Long id,
                         @RequestParam(name = "fixedStartTime", required = false) Date fixedStartTime,
                         @RequestParam(name = "fixedEndTime", required = false) Date fixedEndTime,
                         @RequestParam(name = "scheduleType", required = false) String scheduleType,
                         @RequestParam(name = "windowStartTime", required = false) Date windowStartTime,
                         @RequestParam(name = "windowEndTime", required = false) Date windowEndTime,
                         @RequestParam(name = "requiredDurationMinutes", required = false) Integer requiredDurationMinutes,
                         @RequestParam(name = "requiredStaffCount", required = false) Integer requiredStaffCount,
                         @RequestParam(name = "lane", required = false) Integer lane,
                         @RequestParam(name = "note", required = false) String note,
                         @RequestParam(name = "active", required = false) Boolean active,
                         @RequestParam("store") String storeCode,
                         @RequestParam("mode") String mode,
                         @RequestParam(name = "day", required = false) Short dayOfWeek,
                         @RequestParam(name = "sd", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate selectedDate,
                         @RequestParam(name = "dept", required = false) String departmentCode,
                         @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        TaskPlan p = planRepository.find(id);
        if (p == null) {
            String redirect = "redirect:/tasks/plan?store=" + storeCode + "&mode=" + mode;
            if ("weekly".equalsIgnoreCase(mode)) {
                redirect += "&day=" + (dayOfWeek == null ? 1 : dayOfWeek);
            } else if (selectedDate != null) {
                redirect += "&sd=" + selectedDate;
            }
            if (departmentCode != null && !departmentCode.isBlank()) redirect += "&dept=" + departmentCode;
            return redirect;
        }
        if (scheduleType != null && !scheduleType.isBlank()) {
            p.setScheduleType(scheduleType);
            // If switching type, clear the other set to avoid inconsistent state
            if ("FIXED".equalsIgnoreCase(scheduleType)) {
                p.setWindowStartTime(null);
                p.setWindowEndTime(null);
                p.setRequiredDurationMinutes(null);
            } else if ("FLEXIBLE".equalsIgnoreCase(scheduleType)) {
                p.setFixedStartTime(null);
                p.setFixedEndTime(null);
            }
        }
        if (fixedStartTime != null) p.setFixedStartTime(fixedStartTime);
        if (fixedEndTime != null) p.setFixedEndTime(fixedEndTime);
        if (windowStartTime != null) p.setWindowStartTime(windowStartTime);
        if (windowEndTime != null) p.setWindowEndTime(windowEndTime);
        if (requiredDurationMinutes != null) p.setRequiredDurationMinutes(requiredDurationMinutes);
        if (requiredStaffCount != null) p.setRequiredStaffCount(requiredStaffCount);
        if (lane != null) p.setLane(lane);
        if (note != null) p.setNote(note);
        if (active != null) p.setActive(active);
        planRepository.update(p);
        String redirect = "redirect:/tasks/plan?store=" + storeCode + "&mode=" + mode;
        if ("weekly".equalsIgnoreCase(mode)) {
            redirect += "&day=" + (dayOfWeek == null ? 1 : dayOfWeek);
        } else if (selectedDate != null) {
            redirect += "&sd=" + selectedDate;
        } else if (from != null && to != null) {
            redirect += "&from=" + from + "&to=" + to;
        }
        if (departmentCode != null && !departmentCode.isBlank()) redirect += "&dept=" + departmentCode;
        return redirect;
    }

    @PostMapping("/plan/{id}/delete")
    public String delete(@PathVariable("id") Long id,
                         @RequestParam("store") String storeCode,
                         @RequestParam("mode") String mode,
                         @RequestParam(name = "day", required = false) Short dayOfWeek,
                         @RequestParam(name = "sd", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate selectedDate,
                         @RequestParam(name = "dept", required = false) String departmentCode,
                         @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        planRepository.delete(id);
        String redirect = "redirect:/tasks/plan?store=" + storeCode + "&mode=" + mode;
        if ("weekly".equalsIgnoreCase(mode)) {
            redirect += "&day=" + (dayOfWeek == null ? 1 : dayOfWeek);
        } else if (selectedDate != null) {
            redirect += "&sd=" + selectedDate;
        } else if (from != null && to != null) {
            // fallback: legacy range
            redirect += "&from=" + from + "&to=" + to;
        }
        if (departmentCode != null && !departmentCode.isBlank()) {
            redirect += "&dept=" + departmentCode;
        }
        return redirect;
    }

    @PostMapping("/plan/copy")
    public String copyPlans(@RequestParam("store") String storeCode,
                            @RequestParam("dept") String departmentCode,
                            @RequestParam("mode") String mode,
                            @RequestParam(name = "day", required = false) Short sourceDayOfWeek,
                            @RequestParam(name = "sd", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sourceDate,
                            @RequestParam(name = "targetDow", required = false) List<Short> targetDows,
                            @RequestParam(name = "targetDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) List<LocalDate> targetDates,
                            @RequestParam(name = "dates", required = false) String datesCsv) {
        // Parse datesCsv if provided (comma/space/newline separated)
        if ((targetDates == null || targetDates.isEmpty()) && datesCsv != null && !datesCsv.isBlank()) {
            String[] parts = datesCsv.split("[\\s,;]+");
            targetDates = new ArrayList<>();
            for (String s : parts) {
                if (s == null || s.isBlank()) continue;
                targetDates.add(LocalDate.parse(s.trim()));
            }
        }
        // Always replace existing plans at targets as per requirements
        boolean replace = true;
        planService.copyFromCurrentView(storeCode, departmentCode, mode, sourceDayOfWeek, sourceDate, targetDows, targetDates, replace);
        String redirect = "redirect:/tasks/plan?store=" + storeCode + "&mode=" + mode;
        if ("weekly".equalsIgnoreCase(mode)) {
            redirect += "&day=" + (sourceDayOfWeek == null ? 1 : sourceDayOfWeek);
        } else if (sourceDate != null) {
            redirect += "&sd=" + sourceDate;
        }
        if (departmentCode != null && !departmentCode.isBlank()) redirect += "&dept=" + departmentCode;
        return redirect;
    }

    // 適用処理は月次シフト最適化のタイミングで実行します
}
