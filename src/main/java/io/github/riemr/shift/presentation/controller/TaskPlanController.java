package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.application.repository.TaskPlanRepository;
import io.github.riemr.shift.application.service.TaskPlanService;
import io.github.riemr.shift.application.repository.DaysMasterRepository;
import io.github.riemr.shift.infrastructure.persistence.entity.DaysMaster;
import io.github.riemr.shift.application.service.TaskMasterService;
import io.github.riemr.shift.infrastructure.persistence.entity.TaskPlan;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Date;

@Controller
@RequestMapping("/tasks")
public class TaskPlanController {
    private final TaskPlanRepository planRepository;
    private final TaskPlanService planService;
    private final TaskMasterService taskMasterService;
    private final DaysMasterRepository daysMasterRepository;

    public TaskPlanController(TaskPlanRepository planRepository,
                              TaskPlanService planService,
                              TaskMasterService taskMasterService,
                              DaysMasterRepository daysMasterRepository) {
        this.planRepository = planRepository;
        this.planService = planService;
        this.taskMasterService = taskMasterService;
        this.daysMasterRepository = daysMasterRepository;
    }

    @GetMapping("/plan")
    public String planIndex(@RequestParam(name = "store", required = false) String storeCode,
                            @RequestParam(name = "mode", required = false, defaultValue = "weekly") String mode,
                            @RequestParam(name = "day", required = false) Short dayOfWeek,
                            @RequestParam(name = "sd", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate selectedDate,
                            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            Model model) {
        model.addAttribute("storeCode", storeCode);
        model.addAttribute("mode", mode);
        model.addAttribute("day", dayOfWeek);
        model.addAttribute("sd", selectedDate);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        if (storeCode != null) {
            if ("weekly".equalsIgnoreCase(mode)) {
                short dow = dayOfWeek == null ? 1 : dayOfWeek;
                model.addAttribute("list", planRepository.listWeeklyByStoreAndDow(storeCode, dow));
            } else {
                // tabs: special dates list from days_master
                model.addAttribute("days", daysMasterRepository.listSpecialByStore(storeCode));
                if (selectedDate != null) {
                    model.addAttribute("list", planRepository.selectSpecialByStoreAndDate(storeCode,
                            Date.from(selectedDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())));
                } else {
                    model.addAttribute("list", java.util.Collections.emptyList());
                }
            }
        } else {
            model.addAttribute("list", java.util.Collections.emptyList());
        }
        model.addAttribute("masters", taskMasterService.list());
        model.addAttribute("form", new TaskPlan());
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

    @PostMapping("/plan")
    public String create(@RequestParam("mode") String mode,
                         @ModelAttribute("form") TaskPlan form) {
        if ("weekly".equalsIgnoreCase(mode)) {
            form.setPlanKind("WEEKLY");
            planRepository.save(form);
            return "redirect:/tasks/plan?store=" + form.getStoreCode() + "&mode=weekly&day=" + (form.getDayOfWeek() == null ? 1 : form.getDayOfWeek());
        } else {
            form.setPlanKind("SPECIAL");
            planRepository.save(form);
            LocalDate d = form.getSpecialDate().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            return "redirect:/tasks/plan?store=" + form.getStoreCode() + "&mode=special&sd=" + d;
        }
    }

    @PostMapping("/plan/{id}/delete")
    public String delete(@PathVariable("id") Long id,
                         @RequestParam("store") String storeCode,
                         @RequestParam("mode") String mode,
                         @RequestParam(name = "day", required = false) Short dayOfWeek,
                         @RequestParam(name = "sd", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate selectedDate,
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
        return redirect;
    }

    // 適用処理は月次シフト最適化のタイミングで実行します
}
