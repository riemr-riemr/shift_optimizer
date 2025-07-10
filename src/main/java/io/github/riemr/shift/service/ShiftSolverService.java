package io.github.riemr.shift.service;

import io.github.riemr.shift.domain.model.ShiftSchedule;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.api.solver.SolverJob;
import org.optaplanner.core.api.solver.SolverStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

@Service
public class ShiftSolverService {

    private final SolverManager<ShiftSchedule, String> solverManager;

    public ShiftSolverService(SolverManager<ShiftSchedule, String> solverManager) {
        this.solverManager = solverManager;
    }

    public ShiftSchedule solve(String problemId, ShiftSchedule problem) throws ExecutionException, InterruptedException {
        SolverJob<ShiftSchedule, String> solverJob = solverManager.solve(problemId, problem);
        return solverJob.getFinalBestSolution();
    }

    public SolverStatus getStatus(String problemId) {
        return solverManager.getSolverStatus(problemId);
    }

    public String generateProblemId() {
        return UUID.randomUUID().toString();
    }

    public void terminate(String problemId) {
        solverManager.terminateEarly(problemId);
    }
}
