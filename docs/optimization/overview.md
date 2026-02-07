# 最適化（割り当て・探索）ロジック概要

本書は、現在のシフト最適化（割り当て・探索）ロジックを開発者向けに整理したものです。対象は「単一店舗・単一部門」での月次最適化です。

## 対象範囲と前提
- 対象: 単一店舗（storeCode 必須）・単一部門（departmentCode 必須）の月次サイクル。
- サイクル開始日は設定値（例: 毎月25日など）で可変。`problemId = yyyyMM` はサイクル開始月基準。
- ステージ構成:
  - ATTENDANCE: 出勤（shift_assignment）を決定。
  - ASSIGNMENT: レジ/部門作業（register_assignment / department_task_assignment）を決定。

## ドメインモデル（OptaPlanner）
- PlanningSolution: `optimization.solution.ShiftSchedule`
  - Score: `HardSoftScore`
  - Problem facts: 需要（register/work）、希望休、スキル、週次・月次設定、パターン、前回結果等
  - Planning entities: `optimization.entity.ShiftAssignmentPlanningEntity`（15分枠×日×レジ/作業）
- PlanningEntity: `ShiftAssignmentPlanningEntity`
  - 計画変数: `assignedEmployee`（nullable = true）
  - 値レンジ: `availableEmployees`（各エンティティの `candidateEmployees` を返す）
  - メタ: `stage`（ATTENDANCE/ASSIGNMENT）、`workKind`（REGISTER_OP/DEPARTMENT_TASK）
- BreakAssignment: 現状 ProblemFact として保持（制約からは参照しない）。

## 候補生成（prepareCandidateEmployees）
`ShiftScheduleService.prepareCandidateEmployees(schedule, stage, cycleStart)`

- 前処理（インデックス）
  - 週次設定（社員×曜日→週次pref）
  - 曜日OFF集合、希望休集合（off/paid_leave）
  - パターン一覧（社員→有効パターン）

- ATTENDANCE（出勤決定）
  - フィルタ: 希望休/有休、曜日OFF、基本時間外（withinWeeklyBase）、パターン外（matchesAnyPattern）を除外。
  - MANDATORY支援: 同一MANDATORY日のスロットを1つ単一候補化して“ピン”に近づける（過度に拘束しない範囲）。

- ASSIGNMENT（作業割当）
  - 上記フィルタに加え、当該スロット時間と重なる出勤ロスター（`shift_assignment`）に載る社員のみ（onDuty）。

- フォールバック
  - 診断のためのフィルタ適用後、候補が空なら「全従業員」を候補として採用（探索継続のため）。
  - 不正な割当は後段の制約で禁止/ペナルティ化。

- ログ/診断
  - フォールバック前に候補0のスロット件数を WARN。
  - ASSIGNMENTでは候補0サンプル（最大50件）で onDuty 集合など詳細を WARN。
  - 問題ロード時に employees/assignments/requests/weeklyPrefs/patterns 件数を INFO。

## 制約（ConstraintProvider: ShiftScheduleConstraintProvider）
- ハード（例）
  - スキル0/1割当禁止（レジ/部門）
  - ダブルブッキング禁止
  - 労働時間制約（日次上限/最小、週次/月次範囲）
  - 連勤上限（一般/ATTENDANCE専用ルール）
  - 希望休遵守
  - 6時間超勤務の休憩必須（スロット間60分ギャップで判定）
  - シフトパターン外勤務禁止
- ソフト（例）
  - 需要バランス（レジ/部門、過不足）
  - スキル高い人優先（レジ/部門）
  - レジ切替最小化、連続配置、負荷均等化、日次勤務者数最小化 など
  - ATTENDANCE: 月次の公休日数レンジ誘導
  - 共通: 未割当ペナルティ（soft）

## 探索設定（OptaPlannerConfig）
- フェーズ
  1. Construction Heuristic
  2. Local Search（LATE_ACCEPTANCE、`spentLimit/2`）
  3. Local Search（TABU）
- 終了条件: `shift.solver.spent-limit`（デフォルト PT5M）
- 制約: Constraint Streams（Drools実装）

## 永続化フロー（ShiftScheduleService.persistResult）
- ATTENDANCE
  - `shift_assignment` のみ保存（従業員×日で連続期間にマージ）。
  - initScore < 0 の場合は保存スキップ。ハード違反は警告しつつ保存継続（要件）。
- ASSIGNMENT
  - `register_assignment`（従業員×日×レジで連続マージ）
  - `shift_assignment`（出勤）
  - `department_task_assignment`（部門指定時）
- 削除→挿入
  - 期間: サイクル開始日～+1ヶ月（半開区間）
  - スコープ: storeCode 必須（単一店舗前提）。
  - トランザクション: Solverコールバック側で `REQUIRES_NEW` を明示（確実にコミット）。

## 事前物質化（TaskPlanService）
- レジ/作業需要、部門タスクの枠を月範囲で作成（店舗・部門スコープ）。
- ASSIGNMENTは ATTENDANCE の出勤結果を前提に onDuty を判定。

## 可視化/診断
- スコア推移: `/shift/api/calc/score-series?month=yyyy-MM&storeCode=&departmentCode=`（2秒ポーリング）
- 現在のジョブ: `/shift/api/calc/active-jobs`
- 出勤保存確認: `/shift/api/attendance/shifts/monthly/{month}?storeCode=`
- ログ:
  - ロード/候補生成/保存で INFO/WARN を詳細出力（件数、削除・挿入行数など）。

## 既知の注意点
- サイクル開始日≠月初の場合、`problemId(yyyyMM)` はサイクル開始月になる。UI/APIは新APIでサーバ側計算を利用。
- 候補0が多い場合はロスター・週次基本時間・パターン・スキル設定の見直しが必要。
- BreakAssignment の厳密な「休憩中勤務禁止」は現状無効化（ギャップ判定＋中央寄せ誘導で代替）。

## 今後の改善余地（例）
- フォールバック段階化（パターン緩和→基本時間緩和→全員）
- 部門特性に合わせた候補フィルタの追加（資格・部署紐付け）
- BreakAssignment の再導入（PlanningEntity 化 + CHのエンティティ選択順）
- 制約重みのチューニング、メトリクスの追加収集

