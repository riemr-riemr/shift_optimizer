package io.github.riemr.shift.domain.model;

import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.domain.solution.PlanningScore;

import java.util.List;

@PlanningSolution
public class ShiftSchedule {

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "employeeRange")
    private List<Employee> employeeList;

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "registerRange")
    private List<Register> registerList;

    @ProblemFactCollectionProperty
    private List<RegisterDemand> demandList;

    @PlanningEntityCollectionProperty
    private List<ShiftAssignment> shiftAssignmentList;

    @PlanningScore
    private HardSoftScore score;

    public ShiftSchedule() {}

    public ShiftSchedule(List<Employee> employeeList, List<Register> registerList,
                         List<RegisterDemand> demandList, List<ShiftAssignment> shiftAssignmentList) {
        this.employeeList = employeeList;
        this.registerList = registerList;
        this.demandList = demandList;
        this.shiftAssignmentList = shiftAssignmentList;
    }

    public List<Employee> getEmployeeList() {
        return employeeList;
    }

    public void setEmployeeList(List<Employee> employeeList) {
        this.employeeList = employeeList;
    }

    public List<Register> getRegisterList() {
        return registerList;
    }

    public void setRegisterList(List<Register> registerList) {
        this.registerList = registerList;
    }

    public List<RegisterDemand> getDemandList() {
        return demandList;
    }

    public void setDemandList(List<RegisterDemand> demandList) {
        this.demandList = demandList;
    }

    public List<ShiftAssignment> getShiftAssignmentList() {
        return shiftAssignmentList;
    }

    public void setShiftAssignmentList(List<ShiftAssignment> shiftAssignmentList) {
        this.shiftAssignmentList = shiftAssignmentList;
    }

    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }
} 
