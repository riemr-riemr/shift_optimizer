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
        // グローバルの終了条件は「経過時間（spent limit）」に統一
        // → フィージブルになっても即終了せず、指定時間まで探索を継続
        return new TerminationConfig()
                .withSpentLimit(solverSpentLimit);
    }
    
    private ConstructionHeuristicPhaseConfig constructionHeuristicPhaseConfig() {
        return new ConstructionHeuristicPhaseConfig();
    }
    
    private LocalSearchPhaseConfig localSearchPhaseConfig() {
        // ローカルサーチは「30秒間スコア改善がなければ早期終了」
        return new LocalSearchPhaseConfig()
                .withTerminationConfig(new TerminationConfig()
                        .withUnimprovedSpentLimit(Duration.ofSeconds(30)));
    }
}
