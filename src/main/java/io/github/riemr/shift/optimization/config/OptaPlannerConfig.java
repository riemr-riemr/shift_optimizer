package io.github.riemr.shift.optimization.config;

import io.github.riemr.shift.optimization.constraint.ShiftScheduleConstraintProvider;
import io.github.riemr.shift.optimization.constraint.AttendanceConstraintProvider;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import io.github.riemr.shift.optimization.solution.ShiftSchedule;
import io.github.riemr.shift.optimization.solution.AttendanceSolution;
import io.github.riemr.shift.optimization.phase.AttendanceInitialSolutionBuilder;
import io.github.riemr.shift.optimization.phase.AssignmentInitialSolutionBuilder;
import io.github.riemr.shift.optimization.service.ProblemKey;
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
import org.optaplanner.core.config.phase.custom.CustomPhaseConfig;
import org.optaplanner.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import org.optaplanner.core.config.constructionheuristic.ConstructionHeuristicType;
// pillar move APIs are not available in current OptaPlanner public config; use standard Change/Swap instead
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
public class OptaPlannerConfig {

    // 共通（ASSIGNMENT 月次など）デフォルト上限
    @Value("${shift.solver.spent-limit:PT30M}")
    private String solverSpentLimit;
    // ATTENDANCE（月次シフト）専用上限（既定: 2分）
    @Value("${shift.attendance.spent-limit:PT2M}")
    private String attendanceSpentLimit;
    // ATTENDANCE 未改善終了（既定: 30秒）
    @Value("${shift.attendance.unimproved-limit:PT30S}")
    private String attendanceUnimprovedLimit;
    // アーリーストッピングを無効化
    // @Value("${shift.solver.unimproved-soft-spent-limit:PT30S}")
    // private Duration unimprovedScoreLimit;

    @Bean
    @ConditionalOnMissingBean(SolverFactory.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public SolverFactory solverFactory() {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(ShiftSchedule.class)
                .withEntityClasses(ShiftAssignmentPlanningEntity.class)
                .withTerminationConfig(terminationConfig());

        // Constraint Streams を設定（ConstraintMatchはバージョン互換のためsetter使用）
        ScoreDirectorFactoryConfig sdf1 = new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(ShiftScheduleConstraintProvider.class);
        solverConfig.setScoreDirectorFactoryConfig(sdf1);

        // カスタム初期解（ASSIGNMENT）→ CH → LS(diversify) → LS(converge)
        CustomPhaseConfig customInitial = new CustomPhaseConfig();
        customInitial.setCustomPhaseCommandClassList(List.of(
                AssignmentInitialSolutionBuilder.class
        ));

        solverConfig.setPhaseConfigList(List.<PhaseConfig>of(
                customInitial,
                constructionHeuristicPhaseConfig(), // カスタム初期解で漏れたエンティティを補完  
                relaxedLocalSearchPhase(),
                strictLocalSearchPhase()
        ));

        return SolverFactory.create(solverConfig);
    }

    @Bean
    @ConditionalOnMissingBean(SolverManager.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public SolverManager solverManager(SolverFactory solverFactory) {
        // Generics を回避し、条件評価時の型解決エラーを防ぐ
        return SolverManager.create(solverFactory);
    }

    // ATTENDANCE（パターン単位）用ソルバー
    @Bean
    public SolverFactory<AttendanceSolution> attendanceSolverFactory() {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(AttendanceSolution.class)
                .withEntityClasses(DailyPatternAssignmentEntity.class)
                // ATTENDANCEは専用の時間上限＋未改善終了を使用
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(parseDurationTolerant(attendanceSpentLimit, java.time.Duration.ofMinutes(2)))
                        .withUnimprovedSpentLimit(parseDurationTolerant(attendanceUnimprovedLimit, java.time.Duration.ofSeconds(30))));

        ScoreDirectorFactoryConfig sdf2 = new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(AttendanceConstraintProvider.class);
        solverConfig.setScoreDirectorFactoryConfig(sdf2);

        // ATTENDANCE: CH + LS（第1段: LATE_ACCEPTANCE で多様化, 第2段: TABU_SEARCH で収束）
        // 共通のムーブ（Change + Swap）
        ChangeMoveSelectorConfig aChange = new ChangeMoveSelectorConfig();
        aChange.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(DailyPatternAssignmentEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));
        aChange.setValueSelectorConfig(new ValueSelectorConfig()
                .withVariableName("assignedEmployee")
                .withSelectionOrder(SelectionOrder.RANDOM));
        SwapMoveSelectorConfig aSwap =
                new SwapMoveSelectorConfig();
        aSwap.setEntitySelectorConfig(new EntitySelectorConfig()
                .withEntityClass(DailyPatternAssignmentEntity.class)
                .withSelectionOrder(SelectionOrder.RANDOM));
        UnionMoveSelectorConfig aUnion =
                new UnionMoveSelectorConfig();
        aUnion.setMoveSelectorList(java.util.Arrays.asList(aChange, aSwap));

        // フェーズ1: LATE_ACCEPTANCE（悪化も一定受理して停滞回避）
        LocalSearchPhaseConfig alsDiversify = new LocalSearchPhaseConfig();
        alsDiversify.setLocalSearchType(LocalSearchType.LATE_ACCEPTANCE);
        alsDiversify.setMoveSelectorConfig(aUnion);
        // 先行フェーズには必ずフェーズ終了条件が必要（全体終了条件だけでは到達不能エラーになる）
        alsDiversify.setTerminationConfig(new TerminationConfig().withSpentLimit(
                parseDurationTolerant(solverSpentLimit, java.time.Duration.ofMinutes(30)).dividedBy(2)));
        //（デフォルト設定の Late Acceptance を使用）

        // フェーズ2: TABU_SEARCH（重複探索を避けつつ収束）
        LocalSearchPhaseConfig alsConverge = new LocalSearchPhaseConfig();
        alsConverge.setLocalSearchType(LocalSearchType.TABU_SEARCH);
        alsConverge.setMoveSelectorConfig(aUnion);
        //（デフォルト設定の Tabu 構成を使用）

        // カスタム初期解生成フェーズ
        CustomPhaseConfig customInitialPhase = new CustomPhaseConfig();
        customInitialPhase.setCustomPhaseCommandClassList(List.of(
            AttendanceInitialSolutionBuilder.class
        ));
        
        // Construction Heuristic で補完
        ConstructionHeuristicPhaseConfig constructionPhase = new ConstructionHeuristicPhaseConfig();
        constructionPhase.setConstructionHeuristicType(
            ConstructionHeuristicType.FIRST_FIT);
        
        solverConfig.setPhaseConfigList(List.of(
                customInitialPhase,    // カスタム初期解生成
                constructionPhase,     // 残りを補完
                alsDiversify,
                alsConverge
        ));
        return SolverFactory.create(solverConfig);
    }

    // ScoreManager は explainScore に利用（デバッグ用途）
    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public org.optaplanner.core.api.score.ScoreManager shiftScoreManager(SolverFactory solverFactory) {
        return org.optaplanner.core.api.score.ScoreManager.create(solverFactory);
    }

    @Bean
    public SolverManager<AttendanceSolution, ProblemKey> attendanceSolverManager(
            SolverFactory<AttendanceSolution> solverFactory) {
        return SolverManager.create(solverFactory);
    }


    private TerminationConfig terminationConfig() {
        // アーリーストッピングを無効化して時間制限のみで実行
        TerminationConfig t = new TerminationConfig().withSpentLimit(parseDurationTolerant(solverSpentLimit, java.time.Duration.ofMinutes(30)));
        // アーリーストッピングのコードをコメントアウト
        // if (unimprovedScoreLimit != null && !unimprovedScoreLimit.isZero() && !unimprovedScoreLimit.isNegative()) {
        //     // OptaPlanner 9.x: 未改善終了は withUnimprovedSpentLimit で設定（ベストスコア未更新の経過時間）
        //     t = t.withUnimprovedSpentLimit(unimprovedScoreLimit);
        // }
        return t;
    }

    private java.time.Duration parseDurationTolerant(String raw, java.time.Duration def) {
        if (raw == null || raw.isBlank()) return def;
        String s = raw.trim();
        try {
            if (s.startsWith("P")) {
                if (s.matches("^PT\\d+$")) s = s + "S"; // fix common mistake
                return java.time.Duration.parse(s);
            }
            String ls = s.toLowerCase();
            if (ls.endsWith("ms")) return java.time.Duration.ofMillis(Long.parseLong(ls.substring(0, ls.length()-2)));
            if (ls.endsWith("s")) return java.time.Duration.ofSeconds(Long.parseLong(ls.substring(0, ls.length()-1)));
            if (ls.endsWith("m")) return java.time.Duration.ofMinutes(Long.parseLong(ls.substring(0, ls.length()-1)));
            if (ls.endsWith("h")) return java.time.Duration.ofHours(Long.parseLong(ls.substring(0, ls.length()-1)));
            if (ls.matches("^\\d+$")) return java.time.Duration.ofSeconds(Long.parseLong(ls));
        } catch (Exception ignore) {}
        return def;
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

        UnionMoveSelectorConfig union =
                new UnionMoveSelectorConfig();
        SwapMoveSelectorConfig swap =
                new SwapMoveSelectorConfig();
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
        ls.setTerminationConfig(new TerminationConfig().withSpentLimit(
                parseDurationTolerant(solverSpentLimit, java.time.Duration.ofMinutes(30)).dividedBy(2)));
        return ls;
    }
}
