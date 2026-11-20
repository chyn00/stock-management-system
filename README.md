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
- 테이블: 애플리케이션 시작 시 Flyway 적용
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

요청 오류는 공통 형식으로 응답합니다.

| HTTP 상태 | 코드 | 발생 조건 |
| --- | --- | --- |
| `400 Bad Request` | `INVALID_REQUEST` | 필수값 누락, 수량 0 이하, 잘못된 JSON 또는 필드 형식 |
| `404 Not Found` | `ITEM_NOT_FOUND` | 등록되지 않은 SKU 출고 또는 상품 조회 |
| `409 Conflict` | `INSUFFICIENT_STOCK` | 출고 요청 수량보다 현재 재고가 적음 |
| `409 Conflict` | `REQUEST_CONFLICT` | 이미 사용된 요청 ID |

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
- **동시성:** 출고는 `quantity >= 요청 수량` 조건을 포함한 원자적 UPDATE로 처리합니다. 같은 재고 행의 갱신은 PostgreSQL이 순서대로 처리하고, DB의 `CHECK (quantity >= 0)`도 음수 수량을 막습니다.

![집계 테이블과 INSERT 이력 분리](docs/diagrams/stock-movement-insert-reason.png)

#### 2. 입고·출고 처리 시 데이터 정합성 보장

- **설계:** 현재 수량은 `stocks`에서 갱신하고, 확정된 증감은 `stock_movements`에 INSERT합니다.
- **정합성:** 두 작업을 같은 PostgreSQL 트랜잭션으로 처리합니다. 재고 갱신이나 movement 기록 중 하나라도 실패하면 둘 다 롤백됩니다.
- **미등록 상품 입고:** 먼저 SKU를 조회하고, 없을 때만 상품을 등록한 뒤 입고를 계속합니다. 상세 정보가 비어 있을 수 있어 `items.review_status` 컬럼에 `NEEDS_REVIEW`를 저장합니다. 실제 보정 기능은 MVP에서 제외했습니다.
- **동시 최초 등록:** `(tenant_id, sku)` 유일 제약과 `ON CONFLICT DO NOTHING`으로 상품 중복을 막고, 각 요청의 수량은 모두 반영합니다.

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

두 클래스 모두 Testcontainers PostgreSQL을 사용합니다. H2를 쓰지 않습니다. movement INSERT 실패 시 전체 롤백되고, 동시 요청 후 `stocks.quantity = SUM(stock_movements.quantity_delta)`가 성립하는지 DB 결과로 확인합니다.

## 확장·회고

### 4. 핵심 아키텍처와 확장 방향

- **기능 확장:** 재고 변경 경로가 추가되어도 `stocks` 갱신과 `stock_movements` INSERT를 같은 트랜잭션으로 묶어 현재 수량과 변경 이력을 함께 보존합니다. 외부 시스템이나 관리자 조정은 변경 출처와 사유를 기록하는 방식으로 확장할 수 있습니다.
- **인스턴스 확장:** 여러 애플리케이션 인스턴스가 PostgreSQL의 같은 재고 행을 기준으로 처리합니다. 로컬 캐시에 재고 정합성을 맡기지 않아 인스턴스 간 값 불일치를 피합니다.
- **성능 확장:** 먼저 PostgreSQL 쿼리와 인덱스를 측정·개선합니다. 읽기 부하가 확인되면 조회 캐시나 읽기 전용 경로를 검토하고, 재고 변경의 원자성과 DB 동기화 방안을 함께 설계합니다.
- **동시성 제어:** 현재는 조건부 UPDATE로 재고 검증과 변경을 원자적으로 처리합니다. 충돌을 사용자에게 알리고 재시켜야 하는 요구가 생기면 낙관적 잠금을, 여러 단계의 변경을 한 구간에서 직렬화해야 한다면 비관적 잠금을 검토합니다. 락 대기와 충돌 빈도를 확인한 뒤 적용합니다.
- **보류한 선택:** Redis의 원자 갱신은 PostgreSQL과의 동기화·복구 설계가 추가되므로 현재 MVP에는 도입하지 않았습니다. 인증 기반 Tenant 식별, 상품 정보 보정, 예약 재고와 물류센터 업무도 요구사항이 생길 때 확장합니다.

현재 범위에서는 단일 PostgreSQL 트랜잭션으로 핵심 정합성을 유지합니다. 아키텍처를 바꾸기 전에 실제 부하와 업무 요구를 확인합니다.

### 개발 도구 사용

| 도구 | 사용 목적 |
| --- | --- |
| Serena MCP | 코드 구조와 변경 위치 탐색 |
| Context7 MCP | 라이브러리 공식 문서와 사용법 확인 |
