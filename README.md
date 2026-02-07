# Shift Optimizer

店舗向けのシフト最適化アプリケーションです。  
Spring Boot + Thymeleaf + MyBatis + OptaPlanner + PostgreSQL で構成されています。

## 技術スタック

- Java 17
- Spring Boot 3.x
- Thymeleaf
- MyBatis (XML Mapper)
- OptaPlanner
- PostgreSQL 16
- Docker / Docker Compose

## 主な機能

- 月次シフト最適化（ATTENDANCE）
- 日次作業割当最適化（ASSIGNMENT）
- 従業員管理、スキルマトリクス管理
- 需要登録（時間帯別）
- CSV マスタ取込（Batch）
- 画面単位の権限制御

## プロジェクト構成

- `src/main/java/io/github/riemr/shift/presentation`: Controller / 画面
- `src/main/java/io/github/riemr/shift/application`: Service / DTO / Repository IF
- `src/main/java/io/github/riemr/shift/infrastructure`: Mapper / Repository 実装 / 永続化
- `src/main/java/io/github/riemr/shift/optimization`: OptaPlanner 設定・制約・解探索
- `src/main/resources/mapper`: MyBatis XML
- `src/main/resources/templates`: Thymeleaf テンプレート
- `docker/postgres/init.sql`: DB初期スキーマ・初期データ

## クイックスタート（Docker）

1. イメージをビルド

```bash
docker compose build
```

2. アプリとDBを起動

```bash
docker compose up --build -d
```

3. ブラウザでアクセス

- `http://localhost:8080`

4. 初期ログイン（開発用）

- ユーザー名: `admin`
- パスワード: `admin`

`docker/postgres/init.sql` で投入される開発用アカウントです。初回ログイン後の変更を推奨します。

## ローカル実行（アプリのみ）

DBは Docker で起動し、アプリをローカルで実行する手順です。

1. DB起動

```bash
docker compose up -d db
```

2. アプリ起動（localプロファイル）

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`src/main/resources/application-local.properties` は `localhost:5432` の Postgres を参照します。

## テスト

```bash
./mvnw test
```

## 開発用コマンド

- MyBatis Generator 実行:

```bash
./mvnw org.mybatis.generator:mybatis-generator-maven-plugin:generate
```

- CSVインポート（batch profile）:

```bash
docker compose --profile batch up data-import
```

## 最適化API（抜粋）

- 最適化開始: `POST /shift/api/calc/start`
- 進捗確認: `GET /shift/api/calc/status/{id}`
- 結果取得: `GET /shift/api/calc/result/{id}`

## 注意事項

- MyBatis Generator 生成物（`src/main/java/.../infrastructure/persistence/entity`）は手編集しないでください。
- 本番では機密情報をコードや `application.properties` に直書きしないでください。
- SQLでは `storeCode` スコープを常に意識してください。
