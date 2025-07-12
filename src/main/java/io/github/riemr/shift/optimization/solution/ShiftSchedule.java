package io.github.riemr.shift.optimization.solution;

import io.github.riemr.shift.domain.Employee;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

import java.util.List;

@PlanningSolution
@Getter
@Setter
@ToString
public class ShiftSchedule {

    /** 値域プロバイダ：社員の候補リスト */
    @ValueRangeProvider(id = "employeeRange")
    private List<Employee> employeeList;

    /** PlanningEntity リスト */
    @PlanningEntityCollectionProperty
    private List<ShiftAssignmentPlanningEntity> assignmentList;

    /** スコア */
    @PlanningScore
    private HardSoftScore score;

    // 空コンストラクタ & フル引数コンストラクタ
    public ShiftSchedule() {
    }

    public ShiftSchedule(List<Employee> employeeList, List<ShiftAssignmentPlanningEntity> assignmentList) {
        this.employeeList = employeeList;
        this.assignmentList = assignmentList;
    }
}