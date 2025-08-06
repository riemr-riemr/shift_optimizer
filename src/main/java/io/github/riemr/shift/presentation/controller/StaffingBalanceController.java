package io.github.riemr.shift.presentation.controller;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import io.github.riemr.shift.application.dto.StaffingBalanceDto;
import io.github.riemr.shift.application.service.StaffingBalanceService;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
@RequestMapping("/staffing-balance")
public class StaffingBalanceController {

    private final StaffingBalanceService staffingBalanceService;

    @GetMapping
    public String index(@RequestParam(required = false) String date, Model model) {
        if (date == null) {
            date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        
        model.addAttribute("selectedDate", date);
        return "staffing-balance/index";
    }

    @GetMapping("/api/balance/{storeCode}/{date}")
    @ResponseBody
    public List<StaffingBalanceDto> getStaffingBalance(
            @PathVariable("storeCode") String storeCode,
            @PathVariable("date") String dateString) {
        System.out.println("API called with storeCode: " + storeCode + ", date: " + dateString);
        try {
            LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
            List<StaffingBalanceDto> result = staffingBalanceService.getStaffingBalance(storeCode, date);
            System.out.println("Returning " + result.size() + " records");
            return result;
        } catch (Exception e) {
            System.err.println("Error in getStaffingBalance: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @GetMapping("/api/balance/{storeCode}/month/{month}")
    @ResponseBody
    public List<StaffingBalanceDto> getMonthlyStaffingBalance(
            @PathVariable("storeCode") String storeCode,
            @PathVariable("month") String monthString) {
        LocalDate startOfMonth = LocalDate.parse(monthString + "-01", DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return staffingBalanceService.getStaffingBalanceForMonth(storeCode, startOfMonth);
    }
}