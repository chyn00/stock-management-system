# Stock Management System

SKU 기준으로 입고·출고를 처리하고 현재 재고와 변경 근거를 관리하는 REST API입니다.

## 시작

### 0. 실행 방법 및 기술 스택

| 항목 | 설정 |
| --- | --- |
| 실행 환경 | Java 21, Docker Compose |
| 애플리케이션 | Spring Boot 4.1.3, Gradle Kotlin DSL |
| 데이터베이스 | PostgreSQL 17, Spring Data JPA, Flyway |
| API 문서 | springdoc-openapi 3.2.0 |
| 테스트 | JUnit Jupiter 5.14.4, Testcontainers 2.0.5 |
| 커넥션 풀 | Spring Boot 기본 HikariCP, 최대 연결 10 |
| 프로젝트 버전 | `0.1.0-SNAPSHOT` |

준비되면 실행합니다.

```bash
SERVER_PORT=18765 ./run-local.sh
```

PostgreSQL을 시작하고 Flyway로 테이블을 준비한 뒤 애플리케이션을 실행합니다. 다른 포트를 쓰려면 `18765`를 사용 가능한 값으로 바꾸세요. Swagger UI는 `http://localhost:18765/swagger-ui.html`에서 엽니다. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `TENANT_ID`로 DB와 Tenant 설정을 바꿀 수 있습니다.

입고·출고·현재 재고 조회 예시입니다. 매 요청마다 새 `requestId`를 사용하고, 조회 경로의 `1`은 입고 응답의 `id`로 바꿉니다.

```bash
API_BASE=http://localhost:18765/api/v1

REQUEST_ID=$(uuidgen)
curl -i -X POST "$API_BASE/stocks/inbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"SKU-001\",\"quantity\":10,\"requestId\":\"$REQUEST_ID\"}"

REQUEST_ID=$(uuidgen)
curl -i -X POST "$API_BASE/stocks/outbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"SKU-001\",\"quantity\":3,\"requestId\":\"$REQUEST_ID\"}"

curl -i "$API_BASE/stocks/1"
```

## 설계

### 1. 설계 요약

응답은 과제 형식인 `id`, `name`, `quantity`를 유지합니다. API 기본 경로는 `/api/v1`입니다.

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

오류 응답 본문 예시입니다.

```json
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

#### 시나리오 기준

| 상황 | 설계 판단 | 장점 | 트레이드오프 |
| --- | --- | --- | --- |
| 미등록 SKU 첫 입고 | 상품이 없으면 자동 등록·입고하고, 보정이 필요한 상품은 `NEEDS_REVIEW`로 표시 | 상품 확인을 기다리지 않아 현장 흐름이 끊기지 않고, 이후 정보를 보정할 수 있음 | 입고 전에 사람 확인을 요구하면 상품 정보는 즉시 정확해지지만 핸즈프리 흐름이 멈춤 |
| 동일 SKU 동시 최초 입고 | `(tenant_id, sku)` 유일 제약으로 중복 상품 등록 방지 | 상품은 하나만 만들고 요청별 수량과 movement는 모두 반영 | 상품 생성 경쟁 시 DB가 충돌 요청을 순서대로 처리 |
| 동시 출고 | `quantity >= 요청 수량` 조건부 UPDATE | 차감 후 수량이 음수가 되지 않음 | 출고 요청은 재고가 실제 부족할 때 `409` 반환 |

#### 기술 설계 기준

| 선택 | 기술적 이유 | 트레이드오프·경계 |
| --- | --- | --- |
| `stocks` 집계와 movement 이력 분리 | 현재 수량은 집계 테이블에서 바로 읽고, 변경 근거는 movement에 보존 | 모든 재고 변경은 같은 트랜잭션을 거쳐야 함 |
| 조건부 원자 UPDATE | 수량 비교와 증감을 DB 한 문장으로 처리. 같은 행 UPDATE는 PostgreSQL이 순서대로 조정 | 같은 상품 요청은 행 갱신 중 대기할 수 있음 |
| PostgreSQL 기본 `READ COMMITTED` | 각 SQL 문장이 시작될 때 커밋된 데이터를 읽음. 격리 수준은 별도 변경하지 않음 | 일반 SELECT는 행 잠금이 아니며, 연속 SELECT가 같은 스냅샷을 보장하지 않음 |
| 재고 변경과 movement INSERT를 한 트랜잭션으로 처리 | 어느 한쪽이 실패하면 둘 다 롤백 | 처리 흐름 중 DB 연결을 사용하므로 트랜잭션 안에 외부 호출을 넣지 않음 |
| 재고 조회 DTO projection | `Item`과 `Stock`을 한 JPQL 쿼리로 반환해 추가 조회와 N+1 방지 | 관계 매핑 대신 필요한 필드를 쿼리에 명시 |
| 요청마다 movement `SUM`을 재검사하지 않음 | 별도 SUM 쿼리는 원자 UPDATE의 동시성 방어가 아니며 매 요청 비용 추가 | 현재고와 movement 합계 일치는 통합 테스트에서 확인 |

#### 원자 UPDATE를 선택한 이유

DB가 같은 상품의 재고 행 UPDATE를 순서대로 처리하게 했습니다. 일반적인 경합은 호출자에게 오류로 돌려보내지 않고, 출고는 실제 재고가 부족할 때만 `409`를 반환합니다.

| 대안 | 이번 MVP에서 선택하지 않은 이유 | 재검토 조건 |
| --- | --- | --- |
| `@Version` 낙관적 잠금 | 충돌을 사용자에게 알리고 재시도시켜야 함 | 업무상 다른 요청의 변경을 반드시 인지해야 할 때 |
| `SELECT FOR UPDATE` 명시적 비관적 잠금 | 잠글 키·획득 시점·보유 범위를 정해야 하고 트랜잭션이 길면 대기·경합 가능 | 실제 대기 시간이 커져 단일 UPDATE만으로 부족할 때 |

PostgreSQL 기본 `READ COMMITTED`와 UPDATE의 행 잠금을 사용합니다. `SELECT FOR UPDATE`와 `@Version`은 적용하지 않았고, `CHECK (quantity >= 0)`도 음수 수량을 막습니다. 동시 UPDATE 동작은 [PostgreSQL 기본 격리 수준](https://www.postgresql.org/docs/17/transaction-iso.html)에 따릅니다.

#### 재고 변경을 보존하는 설계 판단

![재고 변경 기록을 보존하는 설계](docs/diagrams/stock-movement-insert-reason.png)

확정된 movement는 DB 트리거로 `UPDATE`·`DELETE`·`TRUNCATE`를 막습니다. 잘못된 기록은 새 `ADJUSTMENT` movement로 정정합니다. 조정 API는 아직 구현하지 않았습니다.

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

### 4. MVP에서 집중한 부분과 남긴 범위

| 이번에 집중하고 검증한 것 | 필요성은 고려했지만 과제 범위 밖으로 둔 것 |
| --- | --- |
| 자동 상품 등록 입고와 동시 최초 SKU 등록 | 인증 및 실제 사용자 기반 Tenant 식별 |
| 음수 재고 방지, 집계·movement 트랜잭션 정합성 | 상품 메타데이터 보정 화면 및 사람 검토 흐름 |
| PostgreSQL 기반 API·동시성·롤백 테스트 | 바코드 확정 업무, 예약 재고, 물류센터 |
| 단일 DB를 여러 애플리케이션 인스턴스의 정합성 기준으로 사용 | 로컬 캐시·Redis·외부 원자 갱신과 DB 간 동기화 |

먼저 PostgreSQL 쿼리와 인덱스를 측정·개선하고, 외부 캐시나 원자 갱신 수단은 성능 근거와 데이터 정합성 설계가 마련된 뒤 검토합니다. 인증 없는 MVP에서 임의 `tenant_id` 헤더를 보안 경계로 사용하지 않습니다.

### 개발 도구 사용

| 도구 | 사용 목적 |
| --- | --- |
| Serena MCP | 코드 구조와 변경 위치 탐색 |
| Context7 MCP | 라이브러리 공식 문서와 사용법 확인 |
