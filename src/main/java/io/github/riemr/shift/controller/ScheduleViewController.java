package io.github.riemr.shift.controller;

import java.time.LocalDate;
import java.util.concurrent.ExecutionException;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.riemr.shift.optimization.service.ShiftScheduleService;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class ScheduleViewController {

    private final ShiftScheduleService scheduleService;

    @GetMapping("/test-schedule")
    public String showMonthlySchedule(@RequestParam("month")
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                      LocalDate month,
                                      Model model) throws ExecutionException, InterruptedException {

        // Solver 実行（同期取得）
        ShiftSchedule schedule = scheduleService.solveMonth(month).get();

        model.addAttribute("schedule", schedule);
        return "test-schedule"; // resources/templates/test-schedule.html
    }
}