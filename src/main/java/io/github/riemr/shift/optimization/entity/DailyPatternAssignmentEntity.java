package io.github.riemr.shift.optimization.entity;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 日次のシフトパターン（連続時間帯）単位での出勤最適化用エンティティ。
 * 同一パターンに必要人数が複数ある場合は unitIndex で複製する。
 */
@PlanningEntity
@Getter
@Setter
@ToString
public class DailyPatternAssignmentEntity {

    @PlanningId
    private String id; // date + "|" + patternStart + "|" + patternEnd + "|" + unitIndex

    private String storeCode;
    private String departmentCode;

    private LocalDate date;
    private LocalTime patternStart;
    private LocalTime patternEnd;
    private int unitIndex;

    // 当該パターン窓に適合する従業員候補（事前計算）
    private java.util.List<Employee> candidateEmployees = java.util.Collections.emptyList();

    @org.optaplanner.core.api.domain.valuerange.ValueRangeProvider(id = "eligibleEmployees")
    public java.util.List<Employee> getEligibleEmployees() {
        return candidateEmployees == null ? java.util.List.of() : candidateEmployees;
    }

    @PlanningVariable(valueRangeProviderRefs = {"eligibleEmployees"}, nullable = true)
    private Employee assignedEmployee; // null = 非出勤

    public DailyPatternAssignmentEntity() {}

    public DailyPatternAssignmentEntity(String id,
                                        String storeCode,
                                        String departmentCode,
                                        LocalDate date,
                                        LocalTime patternStart,
                                        LocalTime patternEnd,
                                        int unitIndex) {
        this.id = id;
        this.storeCode = storeCode;
        this.departmentCode = departmentCode;
        this.date = date;
        this.patternStart = patternStart;
        this.patternEnd = patternEnd;
        this.unitIndex = unitIndex;
    }
}
