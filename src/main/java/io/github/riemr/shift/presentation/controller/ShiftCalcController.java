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
    public String monthlyShift(@RequestParam(required = false) Integer year, 
                              @RequestParam(required = false) Integer month, 
                              @RequestParam(defaultValue = "569") String storeCode, 
                              Model model) {
        if (year == null || month == null) {
            LocalDate today = LocalDate.now();
            year = today.getYear();
            month = today.getMonthValue();
        }

        YearMonth yearMonth = YearMonth.of(year, month);
        List<ShiftAssignmentMonthlyView> monthlyAssignments = service.fetchAssignmentsByMonth(yearMonth.atDay(1));
        Map<Integer, List<String>> dailyShifts =
            monthlyAssignments.stream()
                .sorted(Comparator.comparing(ShiftAssignmentMonthlyView::employeeCode))
                .collect(Collectors.groupingBy(
                    v -> v.date().getDayOfMonth(),
                    Collectors.mapping(ShiftAssignmentMonthlyView::employeeName,
                                    Collectors.collectingAndThen(Collectors.toSet(), ArrayList::new))));

        Map<LocalDate, StaffingBalanceService.DailyStaffingSummary> staffingSummaries = 
            staffingBalanceService.getDailyStaffingSummaryForMonth(storeCode, yearMonth.atDay(1));

        List<List<LocalDate>> weeks = new ArrayList<>();
        LocalDate firstDayOfMonth = yearMonth.atDay(1);
        int firstDayOfWeek = firstDayOfMonth.getDayOfWeek().getValue() % 7; // Sunday is 0
        LocalDate currentDate = firstDayOfMonth.minusDays(firstDayOfWeek);

        for (int i = 0; i < 6; i++) {
            List<LocalDate> week = new ArrayList<>();
            for (int j = 0; j < 7; j++) {
                week.add(currentDate);
                currentDate = currentDate.plusDays(1);
            }
            weeks.add(week);
            if (currentDate.getMonth() != yearMonth.getMonth() && i >= 3) {
                break;
            }
        }

        model.addAttribute("year", year);
        model.addAttribute("month", month);
        model.addAttribute("storeCode", storeCode);
        model.addAttribute("weeks", weeks);
        model.addAttribute("dailyShifts", dailyShifts);
        model.addAttribute("staffingSummaries", staffingSummaries);
        return "shift/monthly-shift";
    }
}