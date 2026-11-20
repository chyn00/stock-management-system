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
API_BASE=http://localhost:18765/api

REQUEST_ID=$(uuidgen)
curl -i -X POST "$API_BASE/stocks/inbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"SKU-001\",\"quantity\":10,\"requestId\":\"$REQUEST_ID\"}"

REQUEST_ID=$(uuidgen)
curl -i -X POST "$API_BASE/stocks/outbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"SKU-001\",\"quantity\":3,\"requestId\":\"$REQUEST_ID\"}"

ITEM_ID=1 # 입고 응답의 id
curl -i "$API_BASE/stocks/$ITEM_ID"
```

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

#### 현재 수량 집계와 변경 이력 분리

![현재 수량 집계와 변경 이력 분리, 정합성 보완](docs/diagrams/stock-movement-insert-reason.png)

#### 기술적 문제와 보완

| 문제 | 보완 |
| --- | --- |
| 집계와 movement 불일치 | `stocks` UPDATE와 movement INSERT를 같은 트랜잭션에서 처리. 한쪽 실패 시 모두 롤백 |
| 동시 출고로 음수 재고 발생 | `quantity >= 요청 수량` 조건부 UPDATE와 `CHECK (quantity >= 0)` 적용 |
| 동시 최초 SKU 등록 | `(tenant_id, sku)` 유일 제약과 `ON CONFLICT DO NOTHING`으로 중복 상품 방지 |
| 재고 조회 추가 쿼리 | `Item`과 `Stock`을 한 DTO projection 쿼리로 조회해 N+1 방지 |

PostgreSQL 기본 `READ COMMITTED`를 사용합니다. 같은 재고 행의 UPDATE는 DB가 순서대로 처리하므로 `@Version`이나 명시적 `SELECT FOR UPDATE` 없이 출고 수량을 보호합니다. [PostgreSQL 기본 격리 동작](https://www.postgresql.org/docs/17/transaction-iso.html)

#### 업무 시나리오

| 상황 | 설계 판단 | 장점 | 트레이드오프 |
| --- | --- | --- | --- |
| 미등록 SKU 첫 입고 | 상세 정보 누락에 대비해 `items.review_status` 컬럼에 `NEEDS_REVIEW` 저장 | 상품 확인을 기다리지 않아 입고 흐름 유지 | 보정 기능은 MVP에서 제외. 입고 전 확인은 정확도를 높이지만 핸즈프리 흐름을 멈춤 |
| 동일 SKU 동시 최초 입고 | `(tenant_id, sku)` 유일 제약으로 중복 상품 등록 방지 | 상품은 하나만 만들고 요청별 수량과 movement는 모두 반영 | 상품 생성 경쟁 시 DB가 충돌 요청을 순서대로 처리 |
| 동시 출고 | `quantity >= 요청 수량` 조건부 UPDATE | 차감 후 수량이 음수가 되지 않음 | 재고가 실제 부족한 요청은 `409` 반환 |

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
