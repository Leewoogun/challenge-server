# 협업 규칙

## Git

- **사용자가 명시적으로 요청하기 전에는 절대 커밋하지 않는다.** "커밋해줘", "commit", "push" 같은 직접 지시가 있을 때만 `git commit` 실행. 스킬 가이드(예: brainstorming의 "commit the design doc")가 자동 커밋을 권하더라도 이 규칙이 우선한다.
- 작업 결과는 staging 까지도 자동으로 하지 않는다. 변경 파일을 보여주고 사용자가 직접 또는 지시 후에 커밋한다.
- `git push`, `--force`, `reset --hard`, 브랜치 삭제 등 파괴적/공유 영향 명령은 항상 사전 확인 필요.

## 아키텍처 (1줄 요약)

Spring Boot + Kotlin 멀티모듈 + 헥사고날 + DIP.
의존 화살표: `app → controller → service → domain/repository(port) ← infra/repositoryimpl, infra/external (adapter)` ; `infra/repositoryimpl → infra/jpa → infra/entity → domain/model`.
모듈 의존 규칙·패키지 컨벤션·어떤 클래스를 어디에 두는지는 `.claude/skills/module-conventions/` 와 `.claude/skills/dip-architecture/` 참조.

## 하네스 (에이전트 + 스킬)

`.claude/agents/` 와 `.claude/skills/` 에 4인 팀(architect / domain-builder / infra-builder / architecture-guard) + 5개 스킬이 정의되어 있다.

- **신규 기능 / API / 도메인 추가 요청** → `add-feature` 스킬이 자동 트리거되어 4인 팀 협업 흐름을 실행한다.
- **단독 질문** ("이 클래스 어디에 둬야 해?", "포트 어떻게 작성?", "응답 형식 어떻게?") → 다음 스킬이 매칭에 따라 자동 트리거된다:
  - `module-conventions` — 코드 위치 / 모듈 의존
  - `dip-architecture` — 포트/어댑터, DIP, 외부 시스템 연동
  - `kotlin-spring-style` — Kotlin/Spring 코드 스타일
  - `api-response-convention` — BaseResponse, 에러 코드, BusinessException, OpenAPI
- 단일 모듈 1~2 파일 수정으로 끝나는 사소한 변경은 `add-feature` 없이 직접 진행해도 된다.

## 빌드 / 테스트

- 전체 빌드: `./gradlew build`
- 모듈 단위 빌드: `./gradlew :service:build :controller:build :app:build` (의존 순서대로 묶음)
- 테스트 실행: `./gradlew test` (모듈별 `:app:test`, `:core:test` 등)
- 신규 기능에 대한 자동 테스트는 **사용자가 명시적으로 요청한 경우에만 작성**한다 (지시받지 않은 일은 하지 않는다).

## Flyway 마이그레이션

- 위치: `app/src/main/resources/db/migration/V{N}__<snake_case>.sql`
- 머지된 마이그레이션 파일은 **절대 수정 금지**. 변경이 필요하면 다음 V 번호로 새 파일 추가.
