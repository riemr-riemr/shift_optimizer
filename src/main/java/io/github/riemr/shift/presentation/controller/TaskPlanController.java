package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.repository.TaskPlanRepository;
import io.github.riemr.shift.application.service.TaskPlanService;
import io.github.riemr.shift.application.service.DepartmentSkillMatrixService;
import io.github.riemr.shift.application.service.TaskMasterService;
import io.github.riemr.shift.application.service.AppSettingService;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import io.github.riemr.shift.infrastructure.mapper.StoreMapper;
import io.github.riemr.shift.infrastructure.mapper.TaskCategoryMasterMapper;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.beans.propertyeditors.CustomDateEditor;

import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.Calendar;

@Controller
@RequestMapping("/tasks")
public class TaskPlanController {
    private final TaskPlanRepository planRepository;
    private final TaskPlanService planService;
    private final TaskMasterService taskMasterService;
    private final DepartmentSkillMatrixService departmentSkillMatrixService;
    private final StoreMapper storeMapper;
    private final TaskCategoryMasterMapper taskCategoryMasterMapper;
    private final AppSettingService appSettingService;

    public TaskPlanController(TaskPlanRepository planRepository,
                              TaskPlanService planService,
                              TaskMasterService taskMasterService,
                              DepartmentSkillMatrixService departmentSkillMatrixService,
                              StoreMapper storeMapper,
                              TaskCategoryMasterMapper taskCategoryMasterMapper,
                              AppSettingService appSettingService) {
        this.planRepository = planRepository;
        this.planService = planService;
        this.taskMasterService = taskMasterService;
        this.departmentSkillMatrixService = departmentSkillMatrixService;
        this.storeMapper = storeMapper;
        this.taskCategoryMasterMapper = taskCategoryMasterMapper;
        this.appSettingService = appSettingService;
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
        // Inject time resolution for UI
        try {
            int res = (appSettingService != null) ? appSettingService.getTimeResolutionMinutes() : 15;
            model.addAttribute("timeResolutionMinutes", res);
        } catch (Exception ignored) { model.addAttribute("timeResolutionMinutes", 15); }
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
                // Special-day mode is no longer supported for task_plan
                model.addAttribute("list", Collections.emptyList());
            }
        } else {
            model.addAttribute("list", Collections.emptyList());
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
                gridResults = Collections.emptyList();
            }
            // Serialize to list of maps
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm");
            List<Map<String,Object>> serialized = new ArrayList<>();
            for (Object o : gridResults) {
                if (o instanceof TaskPlan p) {
                    Map<String,Object> m = new HashMap<>();
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
            model.addAttribute("gridPlans", Collections.emptyList());
        }
        
        return "tasks/plan/index";
    }

    // 特異日追加は廃止

    @PostMapping(value = "/plan")
    public Object create(@RequestParam("mode") String mode,
                         @ModelAttribute("form") TaskPlan form,
                         HttpServletRequest request) {
        // Set safe defaults for quick grid posts
        if (form.getActive() == null) form.setActive(Boolean.TRUE);
        if (form.getRequiredStaffCount() == null) form.setRequiredStaffCount(1);
        // Normalize to configured resolution (server-side safety)
        try {
            int res = (appSettingService != null) ? appSettingService.getTimeResolutionMinutes() : 15;
            normalizeFormTimes(form, res);
        } catch (Exception ignored) {}
        // weekly のみサポート
        if (!"weekly".equalsIgnoreCase(mode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "weekly mode only"));
        }
        planRepository.save(form);
        String redirect = "redirect:/tasks/plan?store=" + form.getStoreCode() + "&mode=weekly&day=" + (form.getDayOfWeek() == null ? 1 : form.getDayOfWeek());
        if (form.getDepartmentCode() != null && !form.getDepartmentCode().isBlank()) {
            redirect += "&dept=" + form.getDepartmentCode();
        }
        if (isAjax(request)) {
            return ResponseEntity.ok(Map.of("id", form.getPlanId()));
        }
        return redirect;
    }

    private boolean isAjax(HttpServletRequest request) {
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
        // Normalize to configured resolution (server-side safety)
        try {
            int res = (appSettingService != null) ? appSettingService.getTimeResolutionMinutes() : 15;
            normalizeFormTimes(p, res);
        } catch (Exception ignored) {}
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
        // Only weekly copy is supported; special mode is ignored
        if ("weekly".equalsIgnoreCase(mode)) {
            planService.copyFromCurrentView(storeCode, departmentCode, "weekly", sourceDayOfWeek, null, targetDows, null, replace);
        }
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

    private void normalizeFormTimes(TaskPlan p, int resMin) {
        if (resMin <= 0) return;
        if (p.getFixedStartTime() != null) p.setFixedStartTime(roundToResolution(p.getFixedStartTime(), resMin));
        if (p.getFixedEndTime() != null) p.setFixedEndTime(roundToResolution(p.getFixedEndTime(), resMin));
        if (p.getWindowStartTime() != null) p.setWindowStartTime(roundToResolution(p.getWindowStartTime(), resMin));
        if (p.getWindowEndTime() != null) p.setWindowEndTime(roundToResolution(p.getWindowEndTime(), resMin));
        if (p.getRequiredDurationMinutes() != null) p.setRequiredDurationMinutes(roundMinutes(p.getRequiredDurationMinutes(), resMin));
    }

    private Date roundToResolution(Date date, int resMin) {
        try {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            int total = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int rounded = Math.max(0, Math.min(24 * 60 - resMin, Math.round((float) total / resMin) * resMin));
            cal.set(Calendar.HOUR_OF_DAY, rounded / 60);
            cal.set(Calendar.MINUTE, rounded % 60);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTime();
        } catch (Exception e) { return date; }
    }

    private Integer roundMinutes(Integer minutes, int resMin) {
        if (minutes == null) return null;
        int m = Math.max(0, minutes);
        return Math.round((float) m / resMin) * resMin;
    }
}
