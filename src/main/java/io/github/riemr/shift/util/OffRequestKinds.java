package io.github.riemr.shift.util;

public final class OffRequestKinds {
    public static final String OFF = "OFF";
    public static final String REQUEST = "REQUEST";
    public static final String PAID = "PAID";

    private OffRequestKinds() {
    }

    public static String normalize(String kind) {
        if (kind == null) return null;
        String upper = kind.trim().toUpperCase();
        if (OFF.equals(upper)) return OFF;
        if (REQUEST.equals(upper)) return REQUEST;
        if (PAID.equals(upper)) return PAID;
        return null;
    }

    public static boolean isDayOff(String kind) {
        return normalize(kind) != null;
    }
}
