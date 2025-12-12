package io.github.riemr.shift.optimization.entity;

import io.github.riemr.shift.infrastructure.persistence.entity.Employee;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.variable.PlanningVariable;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Collections;

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
    private List<Employee> candidateEmployees = Collections.emptyList();

    // 各パターンに対して、そのパターンを持つ従業員のみを候補とする
    @ValueRangeProvider(id = "eligibleEmployees")
    public List<Employee> getEligibleEmployees() {
        return candidateEmployees == null ? List.of() : candidateEmployees;
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
