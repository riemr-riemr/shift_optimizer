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
    @SuppressWarnings({"rawtypes", "unchecked"})
    public SolverFactory<ShiftSchedule> solverFactory() {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(ShiftSchedule.class)
                .withEntityClasses(ShiftAssignmentPlanningEntity.class)
                .withTerminationConfig(terminationConfig());

        // Constraint Streams を設定
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(ShiftScheduleConstraintProvider.class));

        // Phase 設定（OptaPlanner API シグネチャに合わせる）
        solverConfig.setPhaseConfigList(List.<PhaseConfig>of(
                constructionHeuristicPhaseConfig(),
                relaxedLocalSearchPhase(), // フェーズ1: 緩めの受理で多様化
                strictLocalSearchPhase()   // フェーズ2: タブーで収束
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
        // 単一のPlanning Entityクラスなのでデフォルト設定を使用
        return new ConstructionHeuristicPhaseConfig();
    }

    private LocalSearchPhaseConfig strictLocalSearchPhase() {
        LocalSearchPhaseConfig ls = new LocalSearchPhaseConfig();
        ls.setLocalSearchType(LocalSearchType.TABU_SEARCH);
        ChangeMoveSelectorConfig change = new ChangeMoveSelectorConfig();
        change.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(ShiftAssignmentPlanningEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));
        ValueSelectorConfig valueSelector = new ValueSelectorConfig()
                .withVariableName("assignedEmployee")
                .withSelectionOrder(SelectionOrder.RANDOM);
        change.setValueSelectorConfig(valueSelector);

        org.optaplanner.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig swap =
                new org.optaplanner.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig();
        swap.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(ShiftAssignmentPlanningEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));

        org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig union =
                new org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig();
        union.setMoveSelectorList(java.util.Arrays.asList(change, swap));

        ls.setMoveSelectorConfig(union);
        return ls;
    }

    private LocalSearchPhaseConfig relaxedLocalSearchPhase() {
        LocalSearchPhaseConfig ls = new LocalSearchPhaseConfig();
        ls.setLocalSearchType(LocalSearchType.LATE_ACCEPTANCE);
        // 変更ムーブ中心（探索の多様化を優先）
        ChangeMoveSelectorConfig change = new ChangeMoveSelectorConfig();
        change.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(ShiftAssignmentPlanningEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));
        change.setValueSelectorConfig(new ValueSelectorConfig()
                .withVariableName("assignedEmployee")
                .withSelectionOrder(SelectionOrder.RANDOM));
        ls.setMoveSelectorConfig(change);
        // 後続フェーズがあるため、このフェーズ単体の終了条件を必須で設定
        ls.setTerminationConfig(new TerminationConfig().withSpentLimit(solverSpentLimit.dividedBy(2)));
        return ls;
    }
}
