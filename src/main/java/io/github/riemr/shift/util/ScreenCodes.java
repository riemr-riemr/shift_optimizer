package io.github.riemr.shift.util;

import java.util.Map;

/**
 * 画面コード定義（全画面で一元管理）
 */
public final class ScreenCodes {
    private ScreenCodes() {}

    public static final String SHIFT_MONTHLY = "SHIFT_MONTHLY";      // 月次シフト表示 (/shift)
    public static final String SHIFT_DAILY   = "SHIFT_DAILY";        // 日次シフト（最適化）(/shift/calc)
    public static final String EMPLOYEE_LIST = "EMPLOYEE_LIST";      // 従業員管理
    public static final String EMPLOYEE_SHIFT = "EMPLOYEE_SHIFT";    // 従業員個人シフト
    public static final String EMPLOYEE_REQUEST = "EMPLOYEE_REQUEST"; // 希望休入力
    public static final String SKILL_MATRIX  = "SKILL_MATRIX";       // スキルマトリクス
    public static final String REGISTER_DEMAND = "REGISTER_DEMAND";  // 需要予測登録
    public static final String STAFFING_BALANCE = "STAFFING_BALANCE"; // 人員配置過不足
    public static final String SETTINGS      = "SETTINGS";           // アプリ設定 (/settings)
    public static final String SCREEN_PERMISSION = "SCREEN_PERMISSION"; // 画面権限管理

    /**
     * 画面名（表示用）: code -> label
     */
    public static final Map<String, String> NAMES = Map.of(
            SHIFT_MONTHLY, "月次シフト",
            SHIFT_DAILY, "日次シフト（最適化）",
            EMPLOYEE_LIST, "従業員管理",
            EMPLOYEE_SHIFT, "従業員個人シフト",
            EMPLOYEE_REQUEST, "希望休入力",
            SKILL_MATRIX, "スキルマトリクス",
            REGISTER_DEMAND, "需要予測登録",
            STAFFING_BALANCE, "人員配置過不足",
            SETTINGS, "アプリケーション設定",
            SCREEN_PERMISSION, "画面権限管理"
    );
}
