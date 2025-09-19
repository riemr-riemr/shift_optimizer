# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java`: Application code organized by layer
  - `presentation`: Controllers and Thymeleaf views
  - `application`: DTOs, services, repositories (interfaces)
  - `infrastructure`: MyBatis mappers, persistence entities, repository impls
  - `optimization`: OptaPlanner solution, entities, constraints, config
- `src/main/resources`:
  - `mapper/*.xml`: MyBatis SQL mappers
  - `templates/**`: Thymeleaf templates
  - `application.properties`: Local config
- `src/test/java`: Unit/integration tests (Spring Boot + MyBatis)

## Build, Test, and Development Commands
- Build all: `./mvnw clean package`
- Run app: `./mvnw spring-boot:run`
- Run tests: `./mvnw test`
- Generate MyBatis code (if needed): `./mvnw org.mybatis.generator:mybatis-generator-maven-plugin:generate`
- Local DB: `docker-compose up -d` (spins up Postgres used by `application.properties`)

## Coding Style & Naming Conventions
- Java 17, Spring Boot 3.x; prefer Java Time (`java.time.*`) over `Date` for new code.
- Package naming: `io.github.riemr.shift.*`; classes in UpperCamelCase, fields/methods in lowerCamelCase.
- Keep controller logic thin; orchestration in `application.service`.
- Do not hand‑edit MyBatis‑Generator artifacts under `infrastructure/persistence/entity` and base XML result maps; add custom queries in dedicated sections/methods.

## Testing Guidelines
- Frameworks: JUnit (spring‑boot‑starter‑test), MyBatis test starter.
- Place tests mirroring package structure in `src/test/java`.
- Prefer slice tests for mappers and service tests with an ephemeral DB (docker compose or Testcontainers if added).
- Run coverage locally via `./mvnw test`; keep business logic covered by focused unit tests.

## Commit & Pull Request Guidelines
- Use Conventional Commits: `feat:`, `fix:`, `perf:`, `docs:`, `refactor:`, `test:`, `chore:`; add scope where helpful, e.g. `feat(solver): ...`.
- Commits should be small and atomic; include rationale in the body if non‑obvious.
- PRs must include: purpose/summary, screenshots for UI changes, reproduction steps, and links to issues.
- Ensure builds/tests pass; verify SQL changes against a local DB.

## Security & Configuration Tips
- Do not commit secrets. Use environment variables or Spring profiles for prod (`SPRING_PROFILES_ACTIVE=prod`).
- Optimize SQL: prefer range queries over functions on indexed columns; include `storeCode` filters where applicable.
