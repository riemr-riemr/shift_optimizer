package io.github.riemr.shift.controller;

import io.github.riemr.shift.repository.ShiftAssignmentRepository;
import io.github.riemr.shift.domain.model.ShiftAssignment;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Controller
public class ShiftViewController {

    private final ShiftAssignmentRepository assignmentRepository;

    public ShiftViewController(ShiftAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping("/shift/view")
    public String viewShiftAssignments(
            @RequestParam String storeCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model) {

        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);

        List<ShiftAssignment> assignments = assignmentRepository.findByStoreCodeAndStartAtBetween(
                storeCode, start, end);

        model.addAttribute("assignments", assignments);
        model.addAttribute("date", date);
        model.addAttribute("storeCode", storeCode);
        return "shift/view";
    }
}
