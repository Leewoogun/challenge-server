---
name: add-feature
description: challenge-server 에 신규 기능을 추가하거나 기존 기능을 확장할 때 사용. 헥사고날 멀티모듈 구조(domain, infra, service, controller)에 걸친 코드를 architect → domain-builder → infra-builder → architecture-guard 4인 팀이 협업해 작성한다. "기능 추가", "API 만들어줘", "도메인 추가", "엔드포인트 추가", "<X> 기능 구현" 같은 요청이 들어오면 반드시 이 스킬을 사용한다.
---

# Add Feature — 신규 기능 오케스트레이터

challenge-server 에 신규 기능 / 도메인 / API 엔드포인트를 추가할 때 4인 팀을 구성해 헥사고날 구조 전체에 걸친 변경을 조율한다.

## 언제 사용하나

- "회원 차단 기능 추가해줘"
- "챌린지 참여 API 만들어줘"
- "FCM 푸시 도메인 추가"
- 단일 모듈 1~2 파일 수정으로 끝나는 사소한 작업이면 이 스킬 없이 진행해도 된다 (단, 모듈 경계를 넘는다면 사용 권장).

## 워크플로우

### Phase 1: 팀 구성 및 작업 디렉토리 준비

1. 작업 디렉토리 결정: 프로젝트 루트의 `_workspace/<feature-slug>/` (없으면 생성). slug 는 사용자 요청에서 추출 (예: `block-user`, `challenge-join`).
2. **에이전트 호출 방식 결정 (환경 의존)**:
   - **에이전트 팀 모드**: `TeamCreate` / `SendMessage` 도구가 사용 가능하면 4인 팀을 한 번에 구성하고, 팀원 간 직접 통신을 활용한다.
   - **서브 에이전트 모드 (fallback, 기본 안전 경로)**: 위 도구가 없는 환경(현재 Claude Code 기본)에서는 오케스트레이터가 `Agent` 도구로 단계별/병렬 호출하고, 팀원 간 조율은 파일(`_workspace/<feature>/*.md`) 기반으로 한다. SendMessage 가 필요한 자리에선 오케스트레이터가 중간 라우터 역할을 한다.
3. 모든 호출에 `subagent_type: "<agent-name>"` 과 `model: "opus"` 를 명시한다. `.claude/agents/<name>.md` 정의가 자동 로드된다.

### Phase 2: 설계 (architect 단독)

1. `TaskCreate`: "기능 X 의 모듈별 변경 계획 작성" — owner: architect.
2. 오케스트레이터가 architect 호출 (`Agent(subagent_type="architect", model="opus", prompt=...)`). 프롬프트에는 사용자 요구사항 원문 + 작업 디렉토리 경로 + 출력 파일 경로 (`_workspace/<feature>/01_architect_plan.md`) 를 포함.
3. architect 가 계획서 작성 후 종료. 오케스트레이터가 계획서를 읽고 검토. 미결 사항 (10번 항목) 이 있으면 사용자에게 확인. 없으면 Phase 3 으로.

### Phase 3: 구현 (domain-builder → infra-builder, 2단계)

서브 에이전트 모드 (기본):

1. **3-A. 도메인 안쪽 1차 (포트만)**: domain-builder 호출 — 프롬프트에 "1차: 도메인 모델 + 포트 인터페이스만 작성. 서비스는 미루고 `_workspace/<feature>/02_domain_builder_summary.md` 에 포트 시그니처를 기록 후 종료" 지시.
2. **3-B. 병렬 실행 (포트 합의 후)**:
   - domain-builder 호출 (서비스 작성, run_in_background)
   - infra-builder 호출 (entity/jpa/repositoryimpl/external 어댑터 작성, run_in_background)
   - 둘 다 `_workspace/<feature>/02|03_*_summary.md` 갱신
3. **3-C. 컨트롤러**: 두 작업 완료 후 infra-builder 재호출 — service 시그니처 확정 기준으로 컨트롤러 + DTO + SecurityConfig 추가 + Flyway 마이그레이션 작성.

에이전트 팀 모드 (도구 사용 가능 시): 위 단계를 SendMessage 로 자체 조율. 오케스트레이터는 작업 분배 후 모니터링만.

이 부분 병렬 실행이 핵심 이득이다. 포트 시그니처가 인터페이스 합의 지점이므로, 도메인 서비스와 인프라 어댑터가 동시에 작성될 수 있다.

### Phase 4: 검증 (architecture-guard, incremental)

각 모듈 완성 직후 호출 (전체 완성 후 1회 X):

1. domain 모듈들 (`domain/model`, `domain/repository`, `service`) 완성 → architecture-guard 1차 검증 → `_workspace/<feature>/04_qa_report_1.md`.
2. infra 모듈들 (`infra/*`) 완성 → 2차 검증 → `04_qa_report_2.md`.
3. controller + app 완성 → 3차 검증 (전체 빌드 포함) → `04_qa_report_3.md`.

위반 발견 시: 서브 에이전트 모드에서는 오케스트레이터가 보고서를 읽고 담당 빌더(domain-builder/infra-builder) 를 재호출하면서 보고서 경로 + 수정 항목을 프롬프트로 전달. 팀 모드에서는 architecture-guard 가 SendMessage 로 직접 통지. **위반이 0 이 될 때까지 반복** (최대 3 라운드, 초과 시 사용자에게 보고).

### Phase 5: 마이그레이션 + SecurityConfig (필요 시)

infra-builder 가 Phase 3 안에서 처리하나, 이 단계에서 별도 확인:

- Flyway 마이그레이션 파일이 새 V 번호인가?
- 기존 V1__init.sql 이 수정되지 않았는가?
- SecurityConfig 의 신규 endpoint 분류가 계획과 일치하는가?

### Phase 6: 통합 빌드 + 테스트

1. `./gradlew build` 전체 빌드. 실패 시 architecture-guard 가 분류 후 담당 빌더에 회송.
2. 기존 테스트 (`AuthKakaoIntegrationTest`, `AuthControllerTest`, `GlobalExceptionHandlerTest` 등) 가 통과하는지 확인.
3. **새 기능에 대한 자동 테스트는 사용자가 명시적으로 요청한 경우에만 작성** (CLAUDE.md 의 "지시받은 것만" 원칙).

### Phase 7: 보고

오케스트레이터가 사용자에게 다음을 보고:

```
✅ 기능: <이름> 구현 완료

변경된 모듈: domain/model, domain/repository, service, infra/entity, infra/jpa, infra/repositoryimpl, controller, app
신규 엔드포인트: POST /api/v1/...
신규 마이그레이션: V2__add_xxx.sql

QA: 모든 위반 해결 (3 라운드)
빌드: ./gradlew build PASS

산출물: _workspace/<feature>/
```

**커밋은 절대 자동으로 하지 않는다** (CLAUDE.md / MEMORY 의 강제 규칙). 사용자 명시 요청 시에만 git commit.

## 데이터 전달 프로토콜

| 산출물 | 경로 | 작성자 | 소비자 |
|--------|------|--------|--------|
| 변경 계획서 | `_workspace/<feature>/01_architect_plan.md` | architect | domain-builder, infra-builder, architecture-guard |
| 도메인 빌더 요약 | `_workspace/<feature>/02_domain_builder_summary.md` | domain-builder | infra-builder, architecture-guard |
| 인프라 빌더 요약 | `_workspace/<feature>/03_infra_builder_summary.md` | infra-builder | architecture-guard |
| QA 리포트 | `_workspace/<feature>/04_qa_report_<N>.md` | architecture-guard | 모든 빌더 + 오케스트레이터 |

조율 방식:
- **파일 기반 (기본)**: 모든 에이전트는 약속된 경로의 `_workspace/<feature>/*.md` 를 읽고 쓴다. 오케스트레이터가 한 에이전트의 종료 후 다음 에이전트를 호출할 때 해당 파일 경로를 프롬프트에 명시한다.
- **작업 트래킹**: `TaskCreate` / `TaskUpdate` 로 진행 상황을 가시화 (오케스트레이터 책임).
- **실시간 통신 (옵션)**: `SendMessage` 도구가 환경에 있으면 빌더 간 직접 메시지 전달 가능. 없으면 오케스트레이터가 라우터로 동작.

## 에러 핸들링

| 상황 | 대응 |
|------|------|
| architect 가 모호한 요구 → 미결 항목 | 사용자에게 단일 메시지로 확인 후 진행 |
| 빌드 실패 1회 | 담당 빌더가 수정 시도 |
| 빌드 실패 2회 (같은 원인) | 사용자에게 보고하고 진행 보류 |
| QA 위반이 4 라운드 이상 | 사용자에게 보고. 설계 자체에 문제일 가능성 |
| 외부 API 명세 불명 (카카오 같은) | 추측 금지 → 사용자에게 명세 요청 |

## 팀 정리

기능 완료 후 `TeamDestroy` 또는 자연 종료. `_workspace/<feature>/` 는 **삭제하지 않는다** (사후 감사용).

## 참조 스킬

- 모듈별 작성 규칙: `module-conventions`
- DIP / 헥사고날 원칙: `dip-architecture`
- Kotlin/Spring 스타일: `kotlin-spring-style`
- 응답 / 예외 컨벤션: `api-response-convention`

## 테스트 시나리오

### 정상 흐름
사용자: "회원 신고 기능 추가해줘. 신고 사유는 자유 텍스트 200자 이내."
1. architect → 계획서: domain/Report, ReportRepository, ReportService.report(), POST /api/v1/reports, V2__create_reports.sql
2. domain-builder → Report.kt, ReportRepository.kt, ReportService.kt 완성. 포트 작성 후 즉시 infra-builder 통지.
3. infra-builder → ReportEntity, ReportJpaRepository, ReportRepositoryImpl 작성 (병렬). 서비스 완성 후 ReportController + DTO 작성.
4. architecture-guard → 3 라운드 검증, 모두 통과.
5. 사용자 보고.

### 에러 흐름
사용자: "챌린지 추천 알고리즘 추가해줘"
1. architect → 추천 기준이 모호 (인기순? 사용자 선호 기반?)
2. architect 가 미결 항목 작성, 오케스트레이터가 사용자에게 질의.
3. 사용자 답변 후 계획서 갱신, Phase 3 진행.
