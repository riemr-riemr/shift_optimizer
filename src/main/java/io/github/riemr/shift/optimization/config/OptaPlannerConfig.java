package io.github.riemr.shift.optimization.config;

import io.github.riemr.shift.optimization.constraint.ShiftScheduleConstraintProvider;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import org.optaplanner.core.config.heuristic.selector.entity.EntitySelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.value.ValueSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.common.SelectionOrder;
import org.optaplanner.core.config.localsearch.LocalSearchPhaseConfig;
import org.optaplanner.core.config.localsearch.LocalSearchType;
import org.optaplanner.core.config.phase.PhaseConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
public class OptaPlannerConfig {

    // アプリ設定と揃えるため同じキーを参照（デフォルト: PT5M に統一）
    @org.springframework.beans.factory.annotation.Value("${shift.solver.spent-limit:PT5M}")
    private Duration solverSpentLimit;

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(SolverFactory.class)
    public SolverFactory<ShiftSchedule> solverFactory() {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(ShiftSchedule.class)
                .withEntityClasses(ShiftAssignmentPlanningEntity.class)
                .withTerminationConfig(terminationConfig());

        // Constraint Streams を設定
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(ShiftScheduleConstraintProvider.class));

        // Phase 設定
        solverConfig.setPhaseConfigList(List.<PhaseConfig>of(
                constructionHeuristicPhaseConfig(),
                localSearchPhaseConfig()
        ));

        return SolverFactory.create(solverConfig);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(SolverManager.class)
    public SolverManager<ShiftSchedule, io.github.riemr.shift.optimization.service.ProblemKey> solverManager(
            SolverFactory<ShiftSchedule> solverFactory) {
        return SolverManager.create(solverFactory);
    }

    private TerminationConfig terminationConfig() {
        return new TerminationConfig().withSpentLimit(solverSpentLimit);
    }

    private ConstructionHeuristicPhaseConfig constructionHeuristicPhaseConfig() {
        return new ConstructionHeuristicPhaseConfig();
    }

    private LocalSearchPhaseConfig localSearchPhaseConfig() {
        LocalSearchPhaseConfig ls = new LocalSearchPhaseConfig();
        // TABU_SEARCHで探索性向上
        ls.setLocalSearchType(LocalSearchType.TABU_SEARCH);

        // シンプルなChangeMoveSelectorを使用
        ChangeMoveSelectorConfig change = new ChangeMoveSelectorConfig();
        change.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(ShiftAssignmentPlanningEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));
        change.setValueSelectorConfig(new ValueSelectorConfig()
                .withVariableName("assignedEmployee")
                .withSelectionOrder(SelectionOrder.RANDOM));

        ls.setMoveSelectorConfig(change);
        return ls;
    }
}
