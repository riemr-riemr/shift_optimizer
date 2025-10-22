package io.github.riemr.shift.application.service;

import io.github.riemr.shift.application.service.TaskPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

/**
 * シフト最適化の事前準備処理を独立して実行するサービス
 * taskPlanServiceのトランザクション問題を回避するため、
 * 最適化処理とは完全に分離して実行する
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftOptimizationPreparationService {
    
    private final TaskPlanService taskPlanService;
    
    /**
     * シフト最適化のための事前準備を非同期で実行
     * - task_planからwork_demand_intervalへの変換
     * - 部門タスク割当の物質化
     * 
     * @param month サイクル開始日
     * @param storeCode 店舗コード
     * @param departmentCode 部門コード
     * @return 準備が成功したかどうかのCompletableFuture
     */
    @Async("taskPlanExecutor")
    @Transactional
    public CompletableFuture<Boolean> prepareOptimizationDataAsync(LocalDate month, String storeCode, String departmentCode) {
        if (storeCode == null || storeCode.isBlank()) {
            log.info("Store code is empty, skipping task plan preparation");
            return CompletableFuture.completedFuture(true);
        }
        
        boolean success = true;
        LocalDate cycleStart = month;
        LocalDate cycleEndInclusive = month.plusMonths(1).minusDays(1);
        
        try {
            // 1. 作業計画の適用（task_plan → work_demand_interval）
            taskPlanService.applyReplacing(storeCode, cycleStart, cycleEndInclusive, "auto_apply");
            log.info("✅ Applied task plans for store {} from {} to {}", storeCode, cycleStart, cycleEndInclusive);
        } catch (Exception e) {
            log.error("❌ Failed to apply task plans before optimization", e);
            success = false;
        }
        
        // 部門指定がある場合の追加処理
        if (departmentCode != null && !departmentCode.isBlank()) {
            try {
                LocalDate from = cycleStart;
                LocalDate to = cycleStart.plusMonths(1); // 半開区間
                int created = taskPlanService.materializeDepartmentAssignments(storeCode, departmentCode, from, to, "auto_init");
                log.info("✅ Materialized {} department task assignments for store {}, dept {}, range {}..{}", 
                        created, storeCode, departmentCode, from, to);
            } catch (Exception e) {
                log.error("❌ Failed to materialize department assignments", e);
                success = false;
            }
            
            try {
                LocalDate from = cycleStart;
                LocalDate to = cycleStart.plusMonths(1); // 半開区間
                int rows = taskPlanService.materializeWorkDemands(storeCode, departmentCode, from, to);
                log.info("✅ Materialized {} work demand intervals for store {}, dept {}, range {}..{}", 
                        rows, storeCode, departmentCode, from, to);
            } catch (Exception e) {
                log.error("❌ Failed to materialize work demands", e);
                success = false;
            }
        }
        
        log.info("Task plan preparation completed for store={}, dept={}, success={}", storeCode, departmentCode, success);
        return CompletableFuture.completedFuture(success);
    }
}