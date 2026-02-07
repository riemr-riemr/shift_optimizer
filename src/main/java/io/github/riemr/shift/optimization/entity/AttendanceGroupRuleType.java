package io.github.riemr.shift.optimization.entity;

public enum AttendanceGroupRuleType {
    MIN_ON_DUTY,
    NO_SAME_DAY_WORK,
    ALL_OR_NOTHING;

    public static AttendanceGroupRuleType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return AttendanceGroupRuleType.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
