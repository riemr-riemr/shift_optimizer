package io.github.riemr.shift.optimization.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.optaplanner.core.api.solver.SolverJob;
import org.optaplanner.core.api.solver.SolverManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.riemr.shift.domain.Employee;
import io.github.riemr.shift.domain.ShiftAssignment;
import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import io.github.riemr.shift.infrastructure.mapper.ShiftAssignmentMapper;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ShiftScheduleService {

    private final ShiftAssignmentMapper assignmentMapper;
    private final EmployeeMapper employeeMapper;
    private final SolverManager<ShiftSchedule, Long> solverManager;

    /**
     * 指定月のシフトを最適化して非同期に返す。
     *
     * @param month e.g. 2025-07-01
     */
    public CompletableFuture<ShiftSchedule> solveMonth(LocalDate month) {

        // 月初と翌月初を計算
        LocalDate from = month.withDayOfMonth(1);
        LocalDate to = from.plusMonths(1);

        // 1. DB から一括ロード – 遅延ロードを避ける
        List<ShiftAssignment> rawAssignments = assignmentMapper.selectByMonth(from, to);
        List<Employee> employees = employeeMapper.selectAll();

        // 2. ラッパーへ変換
        List<ShiftAssignmentPlanningEntity> planningEntities = rawAssignments.stream()
                .map(ShiftAssignmentPlanningEntity::new)
                .collect(Collectors.toList());

        ShiftSchedule problem = new ShiftSchedule(employees, planningEntities);

        // 3. 一意 ID で solve（yyyyMM を long で持たせる例）
        long problemId = month.getYear() * 100L + month.getMonthValue();

        SolverJob<ShiftSchedule, Long> job = solverManager.solve(problemId, problem);

        // 4. 非同期で最終結果を取得し、例外を CompletableFuture に包む
        return CompletableFuture.supplyAsync(() -> {
            try {
                return job.getFinalBestSolution();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Solver interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Solver failed", e);
            }
        });
    }

    /**
     * Solver 結果を書き戻す – ここではシンプルにバルク更新のイメージだけ示す
     */
    public void persistSolution(ShiftSchedule solved) {
        // Map<shiftId, employeeId>
        Map<Long, String> idMap = solved.getAssignmentList().stream()
                .collect(Collectors.toMap(
                        e -> e.getOrigin().getShiftId(),
                        e -> e.getAssignedEmployee().getEmployeeCode()));

        assignmentMapper.bulkUpdateEmployee(idMap);
    }
}