package io.github.riemr.shift.optimization.constraint;

import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeRequest;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeShiftPattern;
import io.github.riemr.shift.infrastructure.persistence.entity.EmployeeWeeklyPreference;
import io.github.riemr.shift.infrastructure.persistence.entity.RegisterDemandQuarter;
import io.github.riemr.shift.optimization.entity.DailyPatternAssignmentEntity;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

import java.time.LocalTime;
import java.time.ZoneId;

public class AttendanceConstraintProvider implements ConstraintProvider {
    @Override
    public Constraint[] defineConstraints(ConstraintFactory f) {
        return new Constraint[] {
                forbidRequestedDayOff(f),
                forbidWeeklyOffOrOutsideBase(f),
                employeeNotDoubleBooked(f),
                requirePatternAlignment(f),
                headcountBalance(f),
                headcountShortageWhenNone(f)
        };
    }

    // 同一従業員・同一日でパターン時間帯が重なる割当を禁止（ATTENDANCEのハード制約）
    private Constraint employeeNotDoubleBooked(ConstraintFactory f) {
        return f.forEachUniquePair(DailyPatternAssignmentEntity.class,
                        Joiners.equal(e -> e.getAssignedEmployee() != null ? e.getAssignedEmployee().getEmployeeCode() : null,
                                e -> e.getAssignedEmployee() != null ? e.getAssignedEmployee().getEmployeeCode() : null),
                        Joiners.equal(DailyPatternAssignmentEntity::getDate))
                .filter((a, b) -> overlaps(a, b))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Attendance double booking");
    }

    private static boolean overlaps(DailyPatternAssignmentEntity a, DailyPatternAssignmentEntity b) {
        if (a.getAssignedEmployee() == null || b.getAssignedEmployee() == null) return false;
        if (!a.getDate().equals(b.getDate())) return false;
        // [start, end) の重なり
        return a.getPatternStart().isBefore(b.getPatternEnd()) && b.getPatternStart().isBefore(a.getPatternEnd());
    }

    private Constraint forbidRequestedDayOff(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(e -> e.getAssignedEmployee() != null)
                .join(EmployeeRequest.class,
                        Joiners.equal(e -> e.getAssignedEmployee().getEmployeeCode(), EmployeeRequest::getEmployeeCode),
                        Joiners.filtering((e, r) -> "off".equalsIgnoreCase(r.getRequestKind())
                                && toLocalDateSafe(r.getRequestDate()).equals(e.getDate())))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Requested day off");
    }

    private Constraint forbidWeeklyOffOrOutsideBase(ConstraintFactory f) {
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(e -> e.getAssignedEmployee() != null)
                .join(EmployeeWeeklyPreference.class,
                        Joiners.equal(e -> e.getAssignedEmployee().getEmployeeCode(), EmployeeWeeklyPreference::getEmployeeCode),
                        Joiners.filtering((e, p) -> p.getDayOfWeek() != null && p.getDayOfWeek().intValue() == e.getDate().getDayOfWeek().getValue()))
                .filter((e, p) -> {
                    if ("OFF".equalsIgnoreCase(p.getWorkStyle())) return true;
                    if (p.getBaseStartTime() == null || p.getBaseEndTime() == null) return false;
                    LocalTime bs = p.getBaseStartTime().toLocalTime();
                    LocalTime be = p.getBaseEndTime().toLocalTime();
                    return e.getPatternStart().isBefore(bs) || e.getPatternEnd().isAfter(be);
                })
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Weekly off / outside base");
    }

    private Constraint requirePatternAlignment(ConstraintFactory f) {
        // 正しい仕様: 「少なくとも1つの一致する有効パターンが存在しない」場合のみハードペナルティ
        // 以前の実装は、非一致パターンの件数分だけペナルティしてしまい、実質的に全割当を阻害していた
        return f.forEach(DailyPatternAssignmentEntity.class)
                .filter(e -> e.getAssignedEmployee() != null)
                .ifNotExists(EmployeeShiftPattern.class,
                        Joiners.equal(e -> e.getAssignedEmployee().getEmployeeCode(), EmployeeShiftPattern::getEmployeeCode),
                        Joiners.filtering((e, p) -> !Boolean.FALSE.equals(p.getActive())
                                && p.getStartTime().toLocalTime().equals(e.getPatternStart())
                                && p.getEndTime().toLocalTime().equals(e.getPatternEnd())))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Pattern alignment required");
    }

    private Constraint headcountBalance(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .join(DailyPatternAssignmentEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getStoreCode, e -> e.getStoreCode()),
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, e -> java.sql.Date.valueOf(e.getDate())),
                        Joiners.filtering((d, e) -> e.getAssignedEmployee() != null
                                && timeWithin(e, d.getSlotTime())))
                .groupBy((d, e) -> d, org.optaplanner.core.api.score.stream.ConstraintCollectors.countBi())
                .penalize(HardSoftScore.ofSoft(1000), (d, assigned) -> Math.abs(d.getRequiredUnits() - assigned))
                .asConstraint("Quarter headcount balance");
    }

    // 需要スロットに対して誰も割り当てが無い場合のショートペナルティ（不足分を強く誘導）
    private Constraint headcountShortageWhenNone(ConstraintFactory f) {
        return f.forEach(RegisterDemandQuarter.class)
                .ifNotExists(DailyPatternAssignmentEntity.class,
                        Joiners.equal(RegisterDemandQuarter::getStoreCode, e -> e.getStoreCode()),
                        Joiners.equal(RegisterDemandQuarter::getDemandDate, e -> java.sql.Date.valueOf(e.getDate())),
                        Joiners.filtering((d, e) -> e.getAssignedEmployee() != null && timeWithin(e, d.getSlotTime())))
                .penalize(HardSoftScore.ofSoft(2000), RegisterDemandQuarter::getRequiredUnits)
                .asConstraint("Quarter headcount shortage (none)");
    }

    private static boolean timeWithin(DailyPatternAssignmentEntity e, LocalTime slot) {
        return (slot.equals(e.getPatternStart()) || slot.isAfter(e.getPatternStart()))
                && slot.isBefore(e.getPatternEnd());
    }

    private static java.time.LocalDate toLocalDateSafe(java.util.Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Date) return ((java.sql.Date) date).toLocalDate();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
