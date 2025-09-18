package io.github.riemr.shift.optimization.solution;

import io.github.riemr.shift.infrastructure.persistence.entity.ConstraintMaster;
import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRegisterSkill;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.Register;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterAssignment;
import io.github.riemr.shift.optimization.entity.ShiftAssignmentPlanningEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningScore;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;

import java.time.LocalDate;
import java.util.List;

/**
 * Planner 用ソリューションオブジェクト。
 * 複数店舗・月次のシフトを 1 つの問題として解く想定。
 */
@PlanningSolution
@Getter
@Setter
@ToString
public class ShiftSchedule {

    /** 問題識別用 (yyyyMM) */
    private Long problemId;

    /** 月次 (例: 2025‑07‑01) */
    private LocalDate month;

    /** 店舗コード */
    private String storeCode;

    /* === Value range / Problem facts === */

    /** 従業員（PlanningVariable の候補） */
    @ValueRangeProvider(id = "employeeRange")
    private List<Employee> employeeList;

    /** レジスター定義 (参照のみ) */
    @ProblemFactCollectionProperty
    private List<Register> registerList;

    /** 15 分需要 (参照のみ) */
    @ProblemFactCollectionProperty
    private List<RegisterDemandQuarter> demandList;

    /** 希望休日 */
    @ProblemFactCollectionProperty
    private List<EmployeeRequest> employeeRequestList;

    /** 制約設定 (重み・閾値など) */
    @ProblemFactCollectionProperty
    private List<ConstraintMaster> constraintMasterList;

    /** 前回結果 – ウォームスタート用 (参照のみ) */
    @ProblemFactCollectionProperty
    private List<RegisterAssignment> previousAssignmentList;

    /** 従業員スキル */
    @ProblemFactCollectionProperty
    private List<EmployeeRegisterSkill> employeeRegisterSkillList = new java.util.ArrayList<>();

    /* === Planning entities === */

    /** 15分×レジ × 日付 × シフトを割り当てる単位 */
    @PlanningEntityCollectionProperty
    private List<ShiftAssignmentPlanningEntity> assignmentList;

    /* === Score === */

    @PlanningScore
    private HardSoftScore score;

    /* === Constructors === */

    public ShiftSchedule() {
    }

    public ShiftSchedule(Long problemId,
                         LocalDate month,
                         String storeCode,
                         List<Employee> employeeList,
                         List<Register> registerList,
                         List<RegisterDemandQuarter> demandList,
                         List<EmployeeRequest> employeeRequestList,
                         List<ConstraintMaster> constraintMasterList,
                         List<RegisterAssignment> previousAssignmentList,
                         List<EmployeeRegisterSkill> employeeRegisterSkillList,
                         List<ShiftAssignmentPlanningEntity> assignmentList) {
        this.problemId = problemId;
        this.month = month;
        this.storeCode = storeCode;
        this.employeeList = employeeList;
        this.registerList = registerList;
        this.demandList = demandList;
        this.employeeRequestList = employeeRequestList;
        this.constraintMasterList = constraintMasterList;
        this.previousAssignmentList = previousAssignmentList;
        this.employeeRegisterSkillList = employeeRegisterSkillList;
        this.assignmentList = assignmentList;
    }
}
