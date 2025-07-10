package io.github.riemr.shift.controller;

import io.github.riemr.shift.domain.model.ShiftSchedule;
import io.github.riemr.shift.service.ShiftAssignmentSaver;
import io.github.riemr.shift.service.ShiftDataLoaderService;
import io.github.riemr.shift.service.ShiftSolverService;
import org.optaplanner.core.api.solver.SolverStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/shift")
public class ShiftController {

    private final ShiftDataLoaderService dataLoaderService;
    private final ShiftSolverService solverService;
    private final ShiftAssignmentSaver assignmentSaver;

    public ShiftController(ShiftDataLoaderService dataLoaderService, ShiftSolverService solverService, ShiftAssignmentSaver assignmentSaver) {
        this.dataLoaderService = dataLoaderService;
        this.solverService = solverService;
        this.assignmentSaver = assignmentSaver;
    }

    @PostMapping("/solve")
    public ShiftSchedule solve(
        @RequestParam String storeCode,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) throws ExecutionException, InterruptedException {
        ShiftSchedule problem = dataLoaderService.load(storeCode, from, to);
        String problemId = solverService.generateProblemId();
        ShiftSchedule solved = solverService.solve(problemId, problem);
        assignmentSaver.save(solved);
        return solved;
    }

    @GetMapping("/status")
    public SolverStatus status(@RequestParam String problemId) {
        return solverService.getStatus(problemId);
    }

    @PostMapping("/terminate")
    public void terminate(@RequestParam String problemId) {
        solverService.terminate(problemId);
    }
}
