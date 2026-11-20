# Stock Management System

- Java 21과 PostgreSQL로 입고·출고 및 현재 재고를 관리하는 REST API
- 핵심 범위: SKU 자동 등록 입고, 재고 부족 방지 출고, 상품 ID 기준 재고 조회
- 기본 패키지: `com.example.stock`

## 설계 요약

| 데이터 | 역할 | 선택 이유 |
| --- | --- | --- |
| `items` | 고객사별 SKU와 상품 정보 | 상품 메타데이터와 수량 변경 분리 |
| `stocks` | 상품별 현재 수량 | 합계 조회 없이 빠르게 읽고, 원자 UPDATE로 출고 가능 여부 판단 |
| `stock_movements` | 확정된 수량 증감 이력 | INSERT 중심으로 변경 근거를 보존 |

- 재고 수량 갱신과 movement INSERT는 한 PostgreSQL 트랜잭션으로 처리합니다.
- 출고는 `quantity >= 요청 수량` 조건부 UPDATE가 성공할 때만 이력을 추가합니다.
- movement 기록이 실패하면 재고 수량 변경도 롤백합니다.
- `stocks.quantity`와 movement `quantity_delta` 합계를 매 API마다 계산하지 않습니다. 통합 테스트에서 두 값의 정합성을 확인합니다.
- 읽기 모델을 분리한 CQRS 유사 구조지만, 비동기 시스템은 사용하지 않습니다.
- 미등록 SKU는 최소 정보로 등록해 입고하고, 상품명은 추후 보완할 수 있도록 nullable로 둡니다.

상세 내용은 [아키텍처](docs/ARCHITECTURE.md)와 [결정 기록](docs/DECISIONS.md)을 참고하세요.

## 기술 스택

- Java 21, Spring Boot 4.1.3, Gradle Kotlin DSL
- PostgreSQL 17, Spring Data JPA, Flyway
- Swagger UI / OpenAPI: springdoc-openapi 3.2.0
- JUnit Jupiter 5.14.4, Testcontainers 2.0.5
- Docker Compose
- 프로젝트 버전: `0.1.0-SNAPSHOT`

## 실행

- 준비: Java 21, Docker, Docker Compose
- PostgreSQL 기동, Flyway DDL 적용, 애플리케이션 실행:

```bash
./run-local.sh
```

- HTTP 포트는 기본적으로 사용 가능한 임의 포트를 선택합니다. 시작 로그에서 포트를 확인하세요.
- 원하는 포트 지정: `SERVER_PORT=18765 ./run-local.sh`
- Swagger UI: `http://localhost:<실행 포트>/swagger-ui.html`
- OpenAPI 문서: `/v3/api-docs/stock-v1`
- DDL은 [Flyway V1](src/main/resources/db/migration/V1__create_initial_schema.sql)에서 관리하며, 별도 수동 실행은 필요하지 않습니다.
- DB 접속 설정: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- 개발 기본 Tenant ID: `TENANT_ID` 환경 변수, 기본값 `1`
- PostgreSQL 중지: `docker compose stop postgres`

## API (`/api/v1`)

- SKU는 고객사별 식별자이며 앞뒤 공백을 제거하고 대소문자를 구분합니다.
- 응답의 `id`는 내부 상품 ID입니다.
- 입고·출고 성공 응답은 `id`, `name`, `quantity`를 반환합니다.

### 입고

- `POST /api/v1/stocks/inbound`
- 필수: `sku`, 양수 `quantity`, UUID `requestId`
- 선택: `name`, `reason`
- 미등록 SKU면 상품 등록과 입고를 함께 처리합니다. 상품명이 없으면 `name`은 `null`입니다.
- 기존 SKU 입고는 기존 상품 정보를 덮어쓰지 않습니다.

```json
{
  "sku": "SKU-001",
  "quantity": 10,
  "requestId": "11111111-1111-1111-1111-111111111111"
}
```

### 출고

- `POST /api/v1/stocks/outbound`
- 필수: `sku`, 양수 `quantity`, UUID `requestId`
- 선택: `reason`
- 미등록 SKU는 `404`, 재고 부족은 `409`입니다.

```json
{
  "sku": "SKU-001",
  "quantity": 3,
  "requestId": "22222222-2222-2222-2222-222222222222"
}
```

### 현재 재고 조회

- `GET /api/v1/stocks/{itemId}`
- 응답: `id`, `name`, `quantity`
- 등록되지 않은 상품 ID는 `404`입니다.

```json
{"id":1,"name":null,"quantity":7}
```

### 오류 응답

| 상태 | 조건 |
| --- | --- |
| `400` | 0 이하 수량, 필수값 누락, 잘못된 JSON·UUID |
| `404` | 미등록 상품 출고·조회 |
| `409` | 재고 부족 또는 중복 요청 ID |

오류 응답은 `code`, `message`를 포함합니다.

## 테스트

```bash
./gradlew test
./gradlew build
```

- Testcontainers가 실행하는 PostgreSQL로 HTTP부터 DB까지 통합 테스트합니다. H2는 사용하지 않습니다.
- 신규·기존 SKU 입고, 정상 출고, 재고 부족, 재고 조회와 잘못된 요청을 확인합니다.
- 동시 최초 입고·동시 입고·동시 출고·동일 상품 입고/출고 혼합 요청을 확인합니다.
- 원장 INSERT 실패 시 롤백과 현재 수량/변동 합계 일치를 확인합니다.
- 테스트는 포트 `0`을 사용해 사용 가능한 포트로 기동합니다.

## MVP 범위

- Tenant 데이터 관계는 DB에 반영합니다. 인증이 없는 MVP에서 임의 요청 헤더를 Tenant 보안 경계로 사용하지 않습니다.
- 인증, 상품 정보 보정 API, 예약 재고와 물류센터 업무는 구현 범위에서 제외했습니다.
- 바코드 인식은 상품 식별이며, 인식만으로 재고를 변경하지 않습니다.
- 추가 설계는 필수 입고·출고·조회 흐름과 정합성 검증을 마친 뒤 검토합니다.

## 에이전트 도구 사용

| 도구 | 사용 목적 |
| --- | --- |
| Serena MCP | 프로젝트 작업 지침과 코드 탐색 기능 확인 |
| Context7 MCP | Spring, JUnit, Testcontainers, springdoc 공식 문서에서 호환 버전과 API 사용법 확인 |

문서에서 확인한 라이브러리 사용법은 프로젝트 빌드 및 PostgreSQL 통합 테스트로 확인하는 것을 원칙으로 합니다. 코드·Git 규칙은 [CONVENTIONS](docs/CONVENTIONS.md), 작업 흐름은 [WORKFLOW](docs/WORKFLOW.md)를 참고하세요.
