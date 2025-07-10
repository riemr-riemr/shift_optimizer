package io.github.riemr.shift.solver;

import io.github.riemr.shift.domain.model.*;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;

import java.time.LocalTime;

import static org.optaplanner.core.api.score.stream.Joiners.equal;

/**
 * 既存実装を Lombok (@Data) に合わせて整理し、
 *   1. メソッド参照の型解決エラーを回避するため <code>lambda</code> 式へ置換。
 *   2. Employee からスキルを引く専用メソッドに依存しない実装へ変更。
 */
public class ShiftScheduleConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                uniqueEmployeePerTimeslot(factory),
                uniqueRegisterPerTimeslot(factory),
                insufficientSkillPenalty(factory),
                denyRequestOff(factory),
                unmetDemandPenalty(factory)
        };
    }

    /**
     * 同一従業員が同一スロットで複数の ShiftAssignment に割り当てられていないか。
     */
    private Constraint uniqueEmployeePerTimeslot(ConstraintFactory factory) {
        return factory.from(ShiftAssignment.class)
                .join(ShiftAssignment.class,
                        equal(sa -> sa.getEmployee().getEmployeeCode()), // 従業員コード
                        equal(ShiftAssignment::getStartAt))
                .filter((a, b) -> !a.equals(b))
                .penalize("同一時間帯の重複勤務", HardSoftScore.ONE_HARD);
    }

    /**
     * 同一レジに同一スロットで複数割当されていないか。
     */
    private Constraint uniqueRegisterPerTimeslot(ConstraintFactory factory) {
        return factory.from(ShiftAssignment.class)
                .join(ShiftAssignment.class,
                        equal(sa -> sa.getRegister().getId()), // RegisterId 比較
                        equal(ShiftAssignment::getStartAt))
                .filter((a, b) -> !a.equals(b))
                .penalize("同一レジの重複割当", HardSoftScore.ONE_HARD);
    }

    /**
     * スキルレベル 2 未満の従業員を対象レジに割り当てた場合の HARD 罰則。
     *
     * Employee#getSkillForRegister(...) への依存を無くすため、
     * EmployeeRegisterSkill エンティティを join して評価する。
     */
    private Constraint insufficientSkillPenalty(ConstraintFactory factory) {
        return factory.from(ShiftAssignment.class)
                .join(EmployeeRegisterSkill.class,
                        equal(sa -> sa.getEmployee().getEmployeeCode(), ers -> ers.getEmployee().getEmployeeCode()),
                        equal(sa -> sa.getRegister().getId().getStoreCode(), ers -> ers.getId().getStoreCode()),
                        equal(sa -> sa.getRegister().getId().getRegisterNo(), ers -> ers.getId().getRegisterNo()))
                .filter((sa, skill) -> skill.getSkillLevel() == null || skill.getSkillLevel() < 2)
                .penalize("スキル不足への割当禁止", HardSoftScore.ONE_HARD);
    }

    /**
     * 希望休("off") に割り当ててしまった場合の HARD 罰則。
     */
    private Constraint denyRequestOff(ConstraintFactory factory) {
        return factory.from(ShiftAssignment.class)
                .join(EmployeeRequest.class,
                        equal(sa -> sa.getEmployee().getEmployeeCode(), EmployeeRequest::getEmployeeCode),
                        equal(sa -> sa.getStartAt().toLocalDate(), EmployeeRequest::getRequestDate))
                .filter((shift, request) -> "off".equals(request.getRequestKind()) &&
                        isOverlapping(shift.getStartAt().toLocalTime(), shift.getEndAt().toLocalTime(),
                                request.getFromTime(), request.getToTime()))
                .penalize("希望休への割当禁止", HardSoftScore.ONE_HARD);
    }

    /* ---------- SOFT ----------
     * 必要台数を下回った分だけペナルティ (100 x 不足台数)
     */
    private Constraint unmetDemandPenalty(ConstraintFactory factory) {
        return factory.from(RegisterDemand.class)
                .join(ShiftAssignment.class,
                        equal((RegisterDemand d) -> d.getStore().getStoreCode(), sa -> sa.getId().getStoreCode()),
                        equal(RegisterDemand::getDate, sa -> sa.getStartAt().toLocalDate()),
                        equal(RegisterDemand::getTime, sa -> sa.getStartAt().toLocalTime()))
                .groupBy((demand, sa) -> demand, ConstraintCollectors.countBi())
                .filter((demand, count) -> count < demand.getRequiredUnits())
                .penalize("レジ需要未充足", HardSoftScore.ofSoft(100));
    }

    /* -------------------------------------------------- */
    private static boolean isOverlapping(LocalTime shiftStart, LocalTime shiftEnd,
                                         LocalTime reqStart, LocalTime reqEnd) {
        if (reqStart == null || reqEnd == null) return true; // 終日希望とみなす
        return !shiftEnd.isBefore(reqStart) && !shiftStart.isAfter(reqEnd);
    }
}
