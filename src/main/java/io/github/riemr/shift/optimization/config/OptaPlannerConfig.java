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
    public SolverManager<ShiftSchedule, Long> solverManager(SolverFactory<ShiftSchedule> solverFactory) {
        return SolverManager.create(solverFactory);
    }
    
    private TerminationConfig terminationConfig() {
        return new TerminationConfig()
                // 最大10分で強制終了
                .withSpentLimit(Duration.ofMinutes(10))
                // 0hardスコア（ハード制約違反なし）を保証
                .withBestScoreFeasible(true);
                // Note: UnimprovedStepCountTermination はsolverレベルでは使用不可のため削除
    }
    
    private ConstructionHeuristicPhaseConfig constructionHeuristicPhaseConfig() {
        return new ConstructionHeuristicPhaseConfig();
    }
    
    private LocalSearchPhaseConfig localSearchPhaseConfig() {
        return new LocalSearchPhaseConfig()
                .withTerminationConfig(new TerminationConfig()
                        // Local Searchフェーズで5000ステップ連続でスコア改善がない場合にアーリーストッピング
                        .withUnimprovedStepCountLimit(5000)
                        // フェーズレベルでも最大時間制限を設定（保険）
                        .withSpentLimit(Duration.ofMinutes(9)));
    }
}