# 月次シフト最適化処理フロー

## 概要

月次シフト画面で「月次シフト最適化」ボタンをクリックした際に実行される処理の詳細フローです。

## 処理フロー

### 1. フロントエンド処理

#### ファイル: `src/main/resources/templates/shift/monthly-shift.html`

**ボタンクリック**
```html
<button type="button" id="attendanceOptimizeBtn" class="btn btn-success">
    <i class="bi bi-people"></i> 月次シフト最適化
</button>
```

**JavaScript イベントハンドラー**
```javascript
// ボタンにイベントリスナーを登録
attendanceBtn.addEventListener('click', () => startOptimization('ATTENDANCE'));
```

**最適化開始処理 (startOptimization関数)**
1. 入力値の取得
   - `storeCode`: 店舗コード
   - `departmentCode`: 部門コード  
   - `targetMonth`: 対象月 (yyyy-MM形式)

2. バリデーション
   - 店舗コードが選択されているかチェック

3. モーダル表示
   - 進捗表示用のモーダルダイアログを表示

### 2. 事前準備処理

#### API エンドポイント: `/shift/api/calc/prepare`

**リクエストデータ**
```json
{
    "month": "2025-05",
    "storeCode": "001", 
    "departmentCode": "520"
}
```

#### クラス・メソッド: `ShiftCalcController.prepare()`

**処理内容:**
1. 月データのパース (`LocalDate.parse()`)
2. シフトサイクル開始日の計算 (`computeCycleStart()`)
3. 事前準備サービスの非同期実行

#### クラス・メソッド: `ShiftOptimizationPreparationService.prepareOptimizationDataAsync()`

**処理内容:**
1. **作業計画の適用**: `TaskPlanService.applyReplacing()`
   - `task_plan` テーブルから `work_demand_interval` への変換
   
2. **部門タスク割当の物質化**: `TaskPlanService.materializeDepartmentAssignments()` (部門指定時)
   - 従業員未割当の部門タスク枠を生成
   
3. **作業需要データの生成**: `TaskPlanService.materializeWorkDemands()`
   - 15分単位の作業需要データを生成

### 3. 最適化実行処理

#### API エンドポイント: `/shift/api/attendance/start` (月次シフト最適化の場合)

#### クラス・メソッド: `ShiftCalcController.startAttendance()`

**処理内容:**
1. 月データのパース
2. シフトサイクル開始日の計算
3. 最適化サービスの呼び出し

#### クラス・メソッド: `ShiftScheduleService.startSolveAttendanceMonth()`

**処理内容:**
1. **問題キーの生成**: `ProblemKey(yearMonth, storeCode, departmentCode, stage="ATTENDANCE")`

2. **追加の事前準備処理** (内部で実行):
   - 作業計画の物質化 (`TaskPlanService.applyReplacing()`)
   - 部門タスク割当の物質化 (部門指定時)
   - 作業需要データの生成

3. **既存ジョブのチェック**:
   - 同じ条件のジョブが実行中かチェック
   - 実行中の場合は既存のチケットを返却

4. **OptaPlanner ソルバーの起動**:
   - `attendanceSolverManager.solveAndListen()` でAttendanceSolution最適化を開始
   - リアルタイムのスコア更新とプログレス追跡を設定

#### クラス・メソッド: `ShiftScheduleService.loadAttendanceProblem()`

**処理内容:**
1. **問題データの収集**:
   - 従業員データ (`Employee`)
   - 日付範囲の生成
   - 従業員制約データ (`EmployeeRequest`, `EmployeeMonthlySetting`)

2. **計画エンティティの生成**:
   - `DailyPatternAssignmentEntity`: 従業員×日付の勤怠パターン割当

3. **AttendanceSolution オブジェクトの構築**

### 4. 進捗監視・結果取得

#### API エンドポイント: `/shift/api/calc/status/{problemId}`

**ポーリング処理** (JavaScript `pollOptimizationStatus()`)
- 1秒間隔で最適化ステータスをポーリング
- プログレスバーとメッセージの更新

#### クラス・メソッド: `ShiftScheduleService.getStatus()`

**ステータス情報:**
- `status`: "SOLVING_ACTIVE", "NOT_SOLVING", "FAILED" 
- `progress`: 進捗率 (0-100)
- `phase`: 現在の処理フェーズ
- `hasHardConstraintViolations`: ハード制約違反の有無

### 5. 結果保存

#### OptaPlanner コールバック: `bestSolutionConsumer`

**処理内容:**
1. **スコアの記録**: `recordScorePoint()` でスコア推移を保存
2. **結果の永続化**: `persistAttendanceResult()` 
   - `shift_assignment` テーブルに出勤時間を保存
   - ハード制約違反があっても保存を実行

#### クラス・メソッド: `ShiftScheduleService.persistAttendanceResult()`

**保存処理:**
1. 既存データの削除 (`shiftAssignmentMapper.deleteByMonthAndStore()`)
2. 新しい出勤データの挿入
3. トランザクション管理

## 関連クラス・インターフェース

### 主要クラス

| クラス名 | 役割 |
|---------|------|
| `ShiftCalcController` | RESTエンドポイントの提供、リクエスト処理 |
| `ShiftScheduleService` | OptaPlannerを使った最適化処理の中核 |
| `ShiftOptimizationPreparationService` | 事前準備処理の独立実行 |
| `TaskPlanService` | 作業計画とタスク割当の管理 |
| `AttendanceSolution` | OptaPlannerの勤怠パターン最適化ソリューション |
| `DailyPatternAssignmentEntity` | OptaPlannerの計画エンティティ (従業員×日の勤怠割当) |

### データエンティティ

| エンティティ名 | 用途 |
|---------------|------|
| `Employee` | 従業員マスタ |
| `EmployeeRequest` | 従業員の希望休日・制約 |
| `EmployeeMonthlySetting` | 従業員の月次設定 (勤務時間等) |
| `TaskPlan` | 作業計画マスタ |
| `WorkDemandInterval` | 15分単位の作業需要データ |
| `ShiftAssignment` | 最終的な出勤シフト結果 |

### 設定・制約

| 項目 | 説明 |
|------|------|
| シフトサイクル開始日 | `AppSettingService.getShiftCycleStartDay()` |
| 最適化時間制限 | `shift.solver.spent-limit` (デフォルト: PT2M) |
| 時間解像度 | 15分単位 |

## API エンドポイント一覧

| エンドポイント | メソッド | 用途 |
|---------------|---------|------|
| `/shift/api/calc/prepare` | POST | 事前準備処理 |
| `/shift/api/attendance/start` | POST | 月次シフト最適化開始 |
| `/shift/api/calc/status/{id}` | GET | 最適化進捗取得 |
| `/shift/api/calc/score-series/{id}` | GET | スコア推移取得 |

## 注意事項

1. **ハード制約違反の扱い**: システムは制約違反があっても結果を保存する設計
2. **非同期処理**: 最適化は非同期で実行され、フロントエンドはポーリングで進捗を監視
3. **トランザクション分離**: 事前準備処理は独立したトランザクションで実行
4. **エラーハンドリング**: 各段階でのエラーはログに記録され、フロントエンドに通知