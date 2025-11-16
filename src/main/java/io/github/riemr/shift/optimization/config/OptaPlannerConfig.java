package io.github.riemr.shift.optimization.config;

import io.github.riemr.shift.optimization.constraint.ShiftScheduleConstraintProvider;
import io.github.riemr.shift.optimization.constraint.AttendanceConstraintProvider;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import io.github.riemr.shift.optimization.solution.AttendanceSolution;
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
// pillar move APIs are not available in current OptaPlanner public config; use standard Change/Swap instead
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
    @org.springframework.beans.factory.annotation.Value("${shift.solver.unimproved-soft-spent-limit:PT30S}")
    private Duration unimprovedScoreLimit;

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

    // ATTENDANCE（パターン単位）用ソルバー
    @Bean
    public SolverFactory<AttendanceSolution> attendanceSolverFactory() {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(AttendanceSolution.class)
                .withEntityClasses(DailyPatternAssignmentEntity.class)
                .withTerminationConfig(terminationConfig());

        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(AttendanceConstraintProvider.class));

        // ATTENDANCE: CH + LS（第1段: LATE_ACCEPTANCE で多様化, 第2段: TABU_SEARCH で収束）
        // 共通のムーブ（Change + Swap）
        ChangeMoveSelectorConfig aChange = new ChangeMoveSelectorConfig();
        aChange.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(DailyPatternAssignmentEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));
        aChange.setValueSelectorConfig(new ValueSelectorConfig()
                .withVariableName("assignedEmployee")
                .withSelectionOrder(SelectionOrder.RANDOM));
        org.optaplanner.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig aSwap =
                new org.optaplanner.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig();
        aSwap.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(DailyPatternAssignmentEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));
        org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig aUnion =
                new org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig();
        aUnion.setMoveSelectorList(java.util.Arrays.asList(aChange, aSwap));

        // フェーズ1: LATE_ACCEPTANCE（悪化も一定受理して停滞回避）
        LocalSearchPhaseConfig alsDiversify = new LocalSearchPhaseConfig();
        alsDiversify.setLocalSearchType(LocalSearchType.LATE_ACCEPTANCE);
        alsDiversify.setMoveSelectorConfig(aUnion);
        // 先行フェーズには必ずフェーズ終了条件が必要（全体終了条件だけでは到達不能エラーになる）
        alsDiversify.setTerminationConfig(new TerminationConfig().withSpentLimit(solverSpentLimit.dividedBy(2)));
        //（デフォルト設定の Late Acceptance を使用）

        // フェーズ2: TABU_SEARCH（重複探索を避けつつ収束）
        LocalSearchPhaseConfig alsConverge = new LocalSearchPhaseConfig();
        alsConverge.setLocalSearchType(LocalSearchType.TABU_SEARCH);
        alsConverge.setMoveSelectorConfig(aUnion);
        //（デフォルト設定の Tabu 構成を使用）

        solverConfig.setPhaseConfigList(List.of(
                new ConstructionHeuristicPhaseConfig(),
                alsDiversify,
                alsConverge
        ));
        return SolverFactory.create(solverConfig);
    }

    @Bean
    public SolverManager<AttendanceSolution, io.github.riemr.shift.optimization.service.ProblemKey> attendanceSolverManager(
            SolverFactory<AttendanceSolution> solverFactory) {
        return SolverManager.create(solverFactory);
    }

    private TerminationConfig terminationConfig() {
        TerminationConfig t = new TerminationConfig().withSpentLimit(solverSpentLimit);
        if (unimprovedScoreLimit != null && !unimprovedScoreLimit.isZero() && !unimprovedScoreLimit.isNegative()) {
            // OptaPlanner 9.x: 未改善終了は withUnimprovedSpentLimit で設定（ベストスコア未更新の経過時間）
            t = t.withUnimprovedSpentLimit(unimprovedScoreLimit);
        }
        return t;
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

        org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig union =
                new org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig();
        org.optaplanner.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig swap =
                new org.optaplanner.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig();
        swap.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(ShiftAssignmentPlanningEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));
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
