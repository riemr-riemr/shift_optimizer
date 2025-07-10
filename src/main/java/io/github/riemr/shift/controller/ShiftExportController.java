package io.github.riemr.shift.controller;

import io.github.riemr.shift.domain.model.ShiftAssignment;
import io.github.riemr.shift.repository.ShiftAssignmentRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
public class ShiftExportController {

    private final ShiftAssignmentRepository assignmentRepository;

    public ShiftExportController(ShiftAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping("/api/shift/export")
    public void exportCsv(
            @RequestParam String storeCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletResponse response) throws Exception {

        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.atTime(LocalTime.MAX);
        List<ShiftAssignment> assignments = assignmentRepository.findByStoreCodeAndStartAtBetween(storeCode, from, to);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=shift_" + storeCode + "_" + date + ".csv");
        PrintWriter writer = response.getWriter();
        writer.println("従業員コード,従業員名,レジ番号,レジ名,開始時刻,終了時刻");

        for (ShiftAssignment a : assignments) {
            String line = String.join(",",
                a.getEmployee() != null ? a.getEmployee().getEmployeeCode() : "",
                a.getEmployee() != null ? a.getEmployee().getEmployeeName() : "",
                a.getRegister() != null ? String.valueOf(a.getRegister().getId().getRegisterNo()) : "",
                a.getRegister() != null ? a.getRegister().getRegisterName() : "",
                a.getStartAt().toLocalTime().toString(),
                a.getEndAt().toLocalTime().toString()
            );
            writer.println(line);
        }
        writer.flush();
    }
}
