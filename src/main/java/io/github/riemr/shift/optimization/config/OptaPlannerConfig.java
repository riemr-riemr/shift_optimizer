package io.github.riemr.shift.optimization.config;

import io.github.riemr.shift.optimization.constraint.ShiftScheduleConstraintProvider;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.phase.PhaseConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
public class OptaPlannerConfig {

    // アプリ設定と揃えるため同じキーを参照（デフォルト: PT2M）
    @org.springframework.beans.factory.annotation.Value("${shift.solver.spent-limit:PT2M}")
    private Duration solverSpentLimit;

    @Bean
    public SolverFactory<ShiftSchedule> solverFactory() {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(ShiftSchedule.class)
                .withEntityClasses(ShiftAssignmentPlanningEntity.class)
                .withConstraintProviderClass(ShiftScheduleConstraintProvider.class)
                .withTerminationConfig(terminationConfig());
        
        // Phase設定を直接設定
        solverConfig.setPhaseConfigList(List.<PhaseConfig>of(
                constructionHeuristicPhaseConfig(),
                localSearchPhaseConfig()
        ));
        
        return SolverFactory.create(solverConfig);
    }
    
    @Bean
    public SolverManager<ShiftSchedule, io.github.riemr.shift.optimization.service.ProblemKey> solverManager(SolverFactory<ShiftSchedule> solverFactory) {
        return SolverManager.create(solverFactory);
    }
    
    private TerminationConfig terminationConfig() {
        return new TerminationConfig()
                // アプリ設定に合わせる（例: PT2M, PT5M 等）
                .withSpentLimit(solverSpentLimit);
    }
    
    private ConstructionHeuristicPhaseConfig constructionHeuristicPhaseConfig() {
        return new ConstructionHeuristicPhaseConfig();
    }
    
    private LocalSearchPhaseConfig localSearchPhaseConfig() {
        return new LocalSearchPhaseConfig()
                .withTerminationConfig(new TerminationConfig()
                        // Local Searchフェーズで45秒間スコア改善がない場合にアーリーストッピング
                        .withUnimprovedSpentLimit(Duration.ofSeconds(45))
                        // フェーズレベルでも最大時間制限を設定（保険）
                        .withSpentLimit(solverSpentLimit.minus(Duration.ofSeconds(45)).isNegative()
                                ? solverSpentLimit
                                : solverSpentLimit.minus(Duration.ofSeconds(45))));
    }
}
