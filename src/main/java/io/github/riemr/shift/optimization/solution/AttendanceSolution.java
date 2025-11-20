package io.github.riemr.shift.optimization.solution;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeMonthlySetting;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

import java.time.LocalDate;
import java.util.List;

@PlanningSolution
@Getter
@Setter
@ToString
public class AttendanceSolution {

    private Long problemId;
    private LocalDate month;
    private String storeCode;
    private String departmentCode;

    @ValueRangeProvider(id = "employeeRange")
    @ProblemFactCollectionProperty
    private List<Employee> employeeList;
    @ProblemFactCollectionProperty
    private List<EmployeeShiftPattern> employeeShiftPatternList;
    @ProblemFactCollectionProperty
    private List<EmployeeWeeklyPreference> employeeWeeklyPreferenceList;
    @ProblemFactCollectionProperty
    private List<EmployeeRequest> employeeRequestList;
    @ProblemFactCollectionProperty
    private List<RegisterDemandQuarter> demandList;
    @ProblemFactCollectionProperty
    private List<EmployeeMonthlySetting> employeeMonthlySettingList;

    @PlanningEntityCollectionProperty
    private List<DailyPatternAssignmentEntity> patternAssignments;

    @PlanningScore
    private HardSoftScore score;
}
