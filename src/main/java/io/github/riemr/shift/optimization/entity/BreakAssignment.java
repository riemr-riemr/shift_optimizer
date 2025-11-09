package io.github.riemr.shift.optimization.entity;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import lombok.Getter;
import lombok.Setter;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * 従業員×日ごとの60分休憩の開始時刻を決定するエンティティ。
 * breakStartAt から60分間は勤務不可とみなす（制約側で重複を禁止）。
 */
@PlanningEntity
@Getter
@Setter
public class BreakAssignment {

    @PlanningId
    private String id; // employeeCode + ":" + date

    private Employee employee;
    private LocalDate date;

    // 候補の開始時刻リスト（分解能に合わせて事前計算）
    private List<Date> candidateStarts = java.util.Collections.emptyList();

    @PlanningVariable(valueRangeProviderRefs = {"breakStartRange"})
    private Date breakStartAt; // 60分休憩の開始

    public BreakAssignment() {}

    public BreakAssignment(String id, Employee employee, LocalDate date, List<Date> candidateStarts) {
        this.id = id;
        this.employee = employee;
        this.date = date;
        this.candidateStarts = candidateStarts == null ? java.util.List.of() : candidateStarts;
    }

    @ValueRangeProvider(id = "breakStartRange")
    public List<Date> getBreakStartRange() {
        return candidateStarts == null ? java.util.List.of() : candidateStarts;
    }
}

