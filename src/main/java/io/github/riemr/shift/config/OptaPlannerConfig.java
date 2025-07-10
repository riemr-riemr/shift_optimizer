package io.github.riemr.shift.config;

import io.github.riemr.shift.domain.model.ShiftAssignment;
import io.github.riemr.shift.domain.model.ShiftSchedule;
import io.github.riemr.shift.solver.ShiftScheduleConstraintProvider;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.SolverManager;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.SolverConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OptaPlannerConfig {

    @Bean
    public SolverFactory<ShiftSchedule> solverFactory() {
        SolverConfig solverConfig = new SolverConfig();
        solverConfig.setSolutionClass(ShiftSchedule.class);
        solverConfig.setEntityClassList(List.of(ShiftAssignment.class));
        solverConfig.setScoreDirectorFactoryConfig(
            new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(ShiftScheduleConstraintProvider.class)
        );
        return SolverFactory.create(solverConfig);
    }

    @Bean
    public SolverManager<ShiftSchedule, String> solverManager(SolverFactory<ShiftSchedule> solverFactory) {
        return SolverManager.create(solverFactory);
    }
}
