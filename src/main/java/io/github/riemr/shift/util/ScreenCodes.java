package io.github.riemr.shift.util;

import java.util.Map;

/**
 * 画面コード定義（全画面で一元管理）
 */
public final class ScreenCodes {
    private ScreenCodes() {}

    public static final String SHIFT_MONTHLY = "SHIFT_MONTHLY";      // 月次シフト表示 (/shift)
    public static final String SHIFT_DAILY   = "SHIFT_DAILY";        // 日次シフト（最適化）(/shift/daily-shift)
    public static final String EMPLOYEE_LIST = "EMPLOYEE_LIST";      // 従業員管理
    public static final String EMPLOYEE_SHIFT = "EMPLOYEE_SHIFT";    // 従業員個人シフト
    public static final String EMPLOYEE_REQUEST = "EMPLOYEE_REQUEST"; // 希望休入力
    public static final String SKILL_MATRIX  = "SKILL_MATRIX";       // スキルマトリクス
    public static final String REGISTER_DEMAND = "REGISTER_DEMAND";  // 需要予測登録
    public static final String STAFFING_BALANCE = "STAFFING_BALANCE"; // 人員配置過不足
    public static final String TASKS = "TASKS";                      // 店内業務タスク (/tasks)
    public static final String TASK_MASTER = "TASK_MASTER";          // 作業マスタ (/tasks/master)
    public static final String TASK_PLAN = "TASK_PLAN";              // 作業計画（単一画面）(/tasks/plan)
    public static final String SETTINGS      = "SETTINGS";           // アプリ設定 (/settings)
    public static final String SCREEN_PERMISSION = "SCREEN_PERMISSION"; // 画面権限管理
    public static final String CSV_IMPORT   = "CSV_IMPORT";         // CSV取り込み
    public static final String DEPT_SKILL_MATRIX = "DEPT_SKILL_MATRIX"; // 他作業スキルマトリクス

    /**
     * 画面名（表示用）: code -> label
     */
    public static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry(SHIFT_MONTHLY, "月次シフト"),
            Map.entry(SHIFT_DAILY, "日次シフト（最適化）"),
            Map.entry(EMPLOYEE_LIST, "従業員管理"),
            Map.entry(EMPLOYEE_SHIFT, "従業員個人シフト"),
            Map.entry(EMPLOYEE_REQUEST, "希望休入力"),
            Map.entry(SKILL_MATRIX, "レジ担当一覧"),
            Map.entry(REGISTER_DEMAND, "需要予測登録"),
            Map.entry(STAFFING_BALANCE, "人員配置過不足"),
            Map.entry(TASKS, "店内業務タスク"),
            Map.entry(TASK_MASTER, "作業マスタ"),
            Map.entry(TASK_PLAN, "作業計画"),
            Map.entry(SETTINGS, "アプリケーション設定"),
            Map.entry(SCREEN_PERMISSION, "画面権限管理"),
            Map.entry(CSV_IMPORT, "CSV取り込み"),
            Map.entry(DEPT_SKILL_MATRIX, "作業担当一覧")
    );
}
