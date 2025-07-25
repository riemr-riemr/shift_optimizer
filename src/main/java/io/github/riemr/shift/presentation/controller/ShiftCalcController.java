package io.github.riemr.shift.presentation.controller;

import io.github.riemr.shift.optimization.service.ShiftScheduleService;
import io.github.riemr.shift.application.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/shift")
public class ShiftCalcController {

    private final ShiftScheduleService service;
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

    @GetMapping("/api/calc/assignments/{yearMonth}")
    @ResponseBody
    public List<ShiftAssignmentView> getAssignmentsByMonth(@PathVariable("yearMonth") String yearMonth) {
        LocalDate month = LocalDate.parse(yearMonth + "01", DateTimeFormatter.ofPattern("yyyyMMdd"));
        return service.fetchAssignmentsByMonth(month);
    }

    @GetMapping("/api/calc/assignments/daily/{date}")
    @ResponseBody
    public List<ShiftAssignmentView> getAssignmentsByDate(@PathVariable("date") String dateString) {
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        return service.fetchAssignmentsByDate(date);
    }
}