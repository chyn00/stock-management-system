# Stock Management System

SKU 기준으로 입고·출고를 처리하고 현재 재고와 변경 근거를 관리하는 REST API입니다.

## 시작

### 0. 실행 및 기술 스택

| 항목 | 설정 |
| --- | --- |
| 실행 환경 | Java 21, Docker Compose |
| 애플리케이션 | Spring Boot 4.1.3, Gradle Kotlin DSL |
| 데이터베이스 | PostgreSQL 17, Spring Data JPA, Flyway |
| API 문서 | springdoc-openapi 3.2.0 |
| 테스트 | JUnit Jupiter 5.14.4, Testcontainers 2.0.5 |
| 커넥션 풀 | Spring Boot 기본 HikariCP, 최대 연결 10 |
| 프로젝트 버전 | `0.1.0-SNAPSHOT` |

#### 실행

```bash
SERVER_PORT=18765 ./run-local.sh
```

- PostgreSQL: Docker Compose 기동
- PostgreSQL 서버 기본 시간대: `Asia/Seoul` (psql 등 직접 접속 기준)
- 테이블: 애플리케이션 시작 시 Flyway 적용
- DB DDL: [최초 스키마 SQL](src/main/resources/db/migration/V1__create_initial_schema.sql)
- Swagger UI: `http://localhost:18765/swagger-ui.html`
- 설정 환경 변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `TENANT_ID`
- 포트 변경: `SERVER_PORT` 값 변경

#### curl 예시

```bash
set -e
API_BASE=http://localhost:18765/api
SKU="SKU-$(date +%s)-$$"

INBOUND=$(curl -fsS -X POST "$API_BASE/stocks/inbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$SKU\",\"quantity\":10,\"requestId\":\"$(uuidgen)\"}")
printf '%s\n' "$INBOUND"
ITEM_ID=$(printf '%s' "$INBOUND" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -i -X POST "$API_BASE/stocks/outbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$SKU\",\"quantity\":3,\"requestId\":\"$(uuidgen)\"}"

curl -i "$API_BASE/stocks/$ITEM_ID"
```

오류별 실행 예시는 [API 예시](docs/API_EXAMPLES.md)를 참고합니다.

## 설계

### 1. 설계 요약

API 기본 경로: `/api`

| Method | 경로 | 요청 | 성공 응답 |
| --- | --- | --- | --- |
| `POST` | `/stocks/inbound` | `sku`, 양수 `quantity`, UUID `requestId`; 선택 `name`, `reason` | 상품 ID·이름·현재 수량 |
| `POST` | `/stocks/outbound` | `sku`, 양수 `quantity`, UUID `requestId`; 선택 `reason` | 상품 ID·이름·현재 수량 |
| `GET` | `/stocks/{itemId}` | 상품 ID | 상품 ID·이름·현재 수량 |

아래 API 오류는 공통 `{code, message}` 형식으로 응답합니다.

| HTTP 상태 | 코드 | 발생 조건 |
| --- | --- | --- |
| `400 Bad Request` | `INVALID_REQUEST` | 필수값 누락, 수량 0 이하, 잘못된 JSON·필드 또는 경로 ID 형식 |
| `415 Unsupported Media Type` | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 `Content-Type` |
| `404 Not Found` | `ITEM_NOT_FOUND` | 등록되지 않은 SKU 출고 또는 상품 조회 |
| `409 Conflict` | `INSUFFICIENT_STOCK` | 출고 요청 수량보다 현재 재고가 적음 |
| `409 Conflict` | `STOCK_QUANTITY_OVERFLOW` | 입고 후 재고가 DB의 `BIGINT` 범위를 초과 |
| `409 Conflict` | `REQUEST_CONFLICT` | 같은 고객사에서 이미 사용된 요청 ID. 요청 내용과 관계없이 거절 |

성공 응답 예시 (`POST /api/stocks/inbound`)

```json
{"id": 1, "name": null, "quantity": 10}
```

오류 응답 예시 (`400 Bad Request`)

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{"code": "INVALID_REQUEST", "message": "잘못된 요청입니다."}
```

SKU는 고객사별 상품 식별자이며, 응답 `id`는 내부 상품 ID입니다. 미등록 SKU 입고는 상품을 자동 등록한 뒤 같은 요청에서 재고를 반영합니다.

| 테이블 | 책임 |
| --- | --- |
| `items` | 고객사별 SKU와 상품 정보 |
| `stocks` | 상품별 현재 수량. 조회와 원자적 출고 검증에 사용 |
| `stock_movements` | 확정된 입고·출고·조정 증감 기록 |

![입고와 출고의 처리 순서, 트랜잭션 경계, 실패 시 동작](docs/diagrams/stock-flow.png)

테이블별 컬럼과 제약조건을 선택한 이유는 [테이블 설계 문서](docs/DATABASE.md)에 정리했습니다.

### 2. 핵심 설계 이유

#### 1. 상품의 현재 재고 수량 확인

- **설계:** `stocks.quantity`에 상품별 현재 수량을 집계해 저장합니다.
- **효과:** 조회할 때마다 `stock_movements` 전체를 합산하지 않아 현재 수량을 바로 읽습니다. 여러 애플리케이션 인스턴스도 같은 PostgreSQL 값을 기준으로 조회합니다.
- **출고 검증:** `quantity >= 요청 수량` 조건부 원자 UPDATE로 확인과 차감을 함께 처리합니다. 같은 재고 행의 동시 갱신은 PostgreSQL이 순서대로 처리하며, 재고가 부족한 요청은 `409`로 거절합니다.
- **DB 무결성:** `CHECK (quantity >= 0)`은 애플리케이션 외의 쓰기 경로에서도 음수 재고 저장을 막는 마지막 방어선입니다.

![집계 테이블과 INSERT 이력 분리](docs/diagrams/stock-movement-insert-reason.png)

#### 2. 입고·출고 처리 시 데이터 정합성 보장

- **설계:** 현재 수량은 `stocks`에서 갱신하고, 확정된 증감은 `stock_movements`에 INSERT합니다.
- **정합성:** 두 작업을 같은 PostgreSQL 트랜잭션으로 처리합니다. 재고 갱신이나 movement 기록 중 하나라도 실패하면 둘 다 롤백됩니다.
- **미등록 상품 입고:** 먼저 SKU를 조회하고, 없을 때만 상품을 등록한 뒤 입고를 계속합니다. 상세 정보가 비어 있을 수 있어 `items.review_status` 컬럼에 `NEEDS_REVIEW`를 저장합니다. 실제 보정 기능은 MVP에서 제외했습니다.
- **동시 최초 입고:** `(tenant_id, sku)` UNIQUE로 상품은 한 번만 등록됩니다. 동시 INSERT 충돌은 `ON CONFLICT DO NOTHING`으로 오류 없이 처리하고, 각 요청의 수량과 변동 기록은 각각 반영합니다.

#### 3. 동시 요청 안정성

- **출고:** `quantity >= 요청 수량` 조건부 UPDATE로 재고 차감과 검증을 한 문장에서 수행합니다. 재고가 부족한 요청만 `409`를 반환합니다.
- **입고:** 같은 상품 행의 UPDATE는 PostgreSQL 기본 `READ COMMITTED`에서 순서대로 처리됩니다. 낙관적 잠금 오류 후 재시도하게 하거나 명시적 `SELECT FOR UPDATE`를 추가하지 않아도 됩니다.
- **조회:** `Item`과 `Stock`을 한 DTO projection 쿼리로 조회해 N+1을 방지합니다.

## 검증

### 3. 테스트 및 증명

```bash
./gradlew test
./gradlew build
```

| 테스트 클래스 | 확인하는 것 |
| --- | --- |
| `StockApiTest` | HTTP 입고·출고·조회, 입력 오류, 재고 부족, 롤백, OpenAPI |
| `StockConcurrencyIntegrationTest` | 동시 최초 입고, 동시 입고, 동시 출고, 입고·출고 혼합, 현재고와 movement 합계 |
| `StockManagementApplicationTest` | PostgreSQL 기동, Flyway V1 적용, JPA 매핑 시작 확인 |

세 클래스 모두 Testcontainers PostgreSQL을 사용합니다. H2를 쓰지 않습니다. movement INSERT 실패 시 전체 롤백되고, 동시 요청 후 `stocks.quantity = SUM(stock_movements.quantity_delta)`가 성립하는지 DB 결과로 확인합니다.

## 확장·회고

### 4. 확장성 및 회고

과제의 핵심 요구를 구현하고, 실무 적용 시 추가로 판단할 조건과 확장 방향을 정리했습니다.

- **<u>락 방식 확장성 고민</u>**
  - 현재는 조건부 UPDATE로 수량 확인과 갱신을 원자적으로 처리합니다.
  - 충돌 요청을 실패시켜야 하는지, 여러 단계 처리를 직렬화해야 하는지 등 실제 업무 흐름과 경합 수준에 따라 낙관적·비관적 잠금을 검토할 수 있습니다.
  - 더블클릭이나 재시도를 같은 작업으로 처리해야 한다면 락과 별도로 멱등성 키와 응답 재사용을 검토해야 합니다. 현재는 동일 `requestId` 재사용을 `409`로 거절하며, 운영 규칙이 정해지지 않아 완전한 멱등성은 구현하지 않았습니다.
- **<u>원자적 업데이트·Redis 확장성 고민</u>**
  - 현재는 PostgreSQL의 집계 재고와 변동 원장을 한 트랜잭션으로 관리합니다.
  - 분산 환경에서 처리량이나 가용성 요구가 커지면 RDB의 정합성을 유지하면서 Redis 원자 갱신을 보조 수단으로 더하는 방안을 고려할 수 있습니다.
  - Redis 메모리 비용과 RDB 동기화·정합성·장애 복구가 추가로 필요하므로, 실제 성능 근거와 운영 요건을 확인한 뒤 판단해야 합니다.
- **<u>현재 범위에서 멈춘 이유</u>**
  - 과제가 요구한 현재 재고 조회, 입출고 정합성, 동시 요청 방어는 PostgreSQL 트랜잭션과 조건부 UPDATE로 구현했습니다.
  - 자동 등록 상품은 상세 정보가 비어 있을 수 있어 `items.review_status` 컬럼에 `NEEDS_REVIEW`를 기록합니다. 입고를 막지 않고 이후 보정 대상으로 구분하며, 보정 API는 MVP 범위에서 제외했습니다.
  - 현장 시나리오와 부하 기준이 주어지지 않은 상태에서 잠금·캐시를 더하기보다, 검증 가능한 핵심에 집중했습니다.

### 개발 도구 사용

| 도구 | 사용 목적 |
| --- | --- |
| Serena MCP | 코드 구조와 변경 위치 탐색 |
| Context7 MCP | 라이브러리 공식 문서와 사용법 확인 |
