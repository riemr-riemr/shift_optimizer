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
import org.springframework.security.access.prepost.PreAuthorize;

import io.github.riemr.shift.application.dto.StaffingBalanceDto;
import io.github.riemr.shift.application.service.StaffingBalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/staffing-balance")
public class StaffingBalanceController {

    private final StaffingBalanceService staffingBalanceService;

    @GetMapping
    @PreAuthorize("@screenAuth.hasViewPermission(T(io.github.riemr.shift.util.ScreenCodes).STAFFING_BALANCE)")
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
        log.debug("getStaffingBalance called with storeCode: {}, date: {}", storeCode, dateString);
        LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        List<StaffingBalanceDto> result = staffingBalanceService.getStaffingBalance(storeCode, date);
        log.debug("getStaffingBalance returning {} records", result.size());
        return result;
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
