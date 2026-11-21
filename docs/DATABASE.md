# 데이터베이스 설계

업무 테이블은 `items`, `stocks`, `stock_movements` 세 개입니다. 실제 제약조건은 [최초 DDL](../src/main/resources/db/migration/V1__create_initial_schema.sql)에 정의되어 있습니다.

## items

| 컬럼 | 자료형 | NULL | 키·제약 | 기본값 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 불가 | PK; `(tenant_id, id)` 복합 UNIQUE | GENERATED ALWAYS AS IDENTITY |
| `tenant_id` | `BIGINT` | 불가 | `(tenant_id, sku)`, `(tenant_id, id)` 복합 UNIQUE | — |
| `sku` | `VARCHAR(100)` | 불가 | `(tenant_id, sku)` 복합 UNIQUE; 공백 불가 CHECK | — |
| `name` | `VARCHAR(255)` | 가능 | 값이 있으면 공백 불가 CHECK | — |
| `barcode` | `VARCHAR(100)` | 가능 | — | — |
| `price` | `NUMERIC(19, 2)` | 가능 | 0 이상 CHECK | — |
| `metadata` | `JSONB` | 가능 | JSON object CHECK | — |
| `review_status` | `VARCHAR(20)` | 불가 | `NEEDS_REVIEW`, `REVIEWED` CHECK | `NEEDS_REVIEW` |
| `created_at` | `TIMESTAMPTZ` | 불가 | — | `CURRENT_TIMESTAMP` |

## stocks

| 컬럼 | 자료형 | NULL | 키·제약 | 기본값 |
| --- | --- | --- | --- | --- |
| `tenant_id` | `BIGINT` | 불가 | `(tenant_id, item_id)` 복합 PK·복합 FK | — |
| `item_id` | `BIGINT` | 불가 | `(tenant_id, item_id)` 복합 PK·복합 FK | — |
| `quantity` | `BIGINT` | 불가 | 0 이상 CHECK | `0` |

복합 FK 대상은 `items(tenant_id, id)`입니다.

## stock_movements

| 컬럼 | 자료형 | NULL | 키·제약 | 기본값 |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 불가 | PK | GENERATED ALWAYS AS IDENTITY |
| `tenant_id` | `BIGINT` | 불가 | `(tenant_id, item_id)` 복합 FK; `(tenant_id, request_id)` 복합 UNIQUE | — |
| `item_id` | `BIGINT` | 불가 | `(tenant_id, item_id)` 복합 FK | — |
| `event_type` | `VARCHAR(20)` | 불가 | `INBOUND`, `OUTBOUND`, `ADJUSTMENT` CHECK; delta 방향 CHECK | — |
| `source_type` | `VARCHAR(20)` | 불가 | `API`, `EXTERNAL`, `SYSTEM` CHECK | — |
| `quantity_delta` | `BIGINT` | 불가 | 0 불가 CHECK; event별 증감 방향 CHECK | — |
| `request_id` | `UUID` | 불가 | `(tenant_id, request_id)` 복합 UNIQUE | — |
| `reference_type` | `VARCHAR(100)` | 가능 | — | — |
| `reference_id` | `VARCHAR(255)` | 가능 | — | — |
| `reason` | `TEXT` | 가능 | `ADJUSTMENT` 또는 `SYSTEM`이면 필수 CHECK | — |
| `recorded_at` | `TIMESTAMPTZ` | 불가 | — | `CURRENT_TIMESTAMP` |

복합 FK 대상은 `stocks(tenant_id, item_id)`입니다.

## 설계 설명

### 상품과 Tenant 키

- `items.id`는 내부 상품 ID이며, SKU는 `(tenant_id, sku)` 복합 UNIQUE로 고객사 안에서만 중복을 막습니다.
- `(tenant_id, id)` 복합 UNIQUE는 `stocks`가 같은 고객사의 상품을 FK로 참조하도록 둡니다.
- `review_status`는 자동 등록 상품의 정보 확인 상태입니다. 상세 정보 보정 API는 MVP 범위에서 제외했습니다.

### 현재 재고

- `stocks`는 `(tenant_id, item_id)` 복합 PK를 사용합니다. 별도 `stock_id`는 두지 않습니다.
- `quantity`는 조회용 현재 수량입니다. 조건부 UPDATE와 0 이상 CHECK로 출고 시 음수 재고를 막습니다.

### 재고 변동 기록

- `event_type`은 입고·출고·조정 유형, `source_type`은 API·외부 시스템·내부 시스템 중 변경 경로를 구분합니다.
- `quantity_delta`만 기록하며 입고는 양수, 출고는 음수입니다. 현재 API 요청은 `source_type=API`로 기록합니다.
- `(tenant_id, request_id)` UNIQUE는 같은 고객사의 요청 ID 중복 기록을 막습니다. 중복 요청은 `409`로 응답하며 이전 성공 결과를 돌려주는 완전한 멱등성은 구현하지 않았습니다.
- `reason`은 조정이나 시스템 변경의 근거를 보존합니다. 해당 유형의 movement에는 사유를 필수로 둡니다.
- `reference_type`, `reference_id`는 변동을 발생시킨 원본 업무 데이터를 선택적으로 참조합니다.
- `stock_movements`는 INSERT 전용입니다. DB 트리거가 UPDATE·DELETE·TRUNCATE를 거절하고, 정정은 새 보정 기록으로 남깁니다.

## 인덱스와 정합성

- Docker Compose의 PostgreSQL 서버 기본 시간대는 `Asia/Seoul`입니다. psql 등 직접 접속한 세션은 이 기본값을 사용합니다.
- 애플리케이션의 PostgreSQL 세션 시간대는 PgJDBC 연결 시 JVM 기본 시간대의 영향을 받습니다. 현재 API는 시각 값을 응답하지 않습니다.
- `TIMESTAMPTZ`는 절대 시각으로 저장되며, 조회 시 세션 시간대에 맞춰 표현됩니다.
- PK와 UNIQUE 제약에 해당하는 인덱스는 PostgreSQL이 생성합니다.
- 추가 조회 인덱스는 `stock_movements(tenant_id, item_id)`입니다.
- 재고 갱신과 movement INSERT는 한 PostgreSQL 트랜잭션으로 처리합니다. 한쪽이 실패하면 전체를 롤백합니다.
- 초기 수량 0을 기준으로 `stocks.quantity`와 `SUM(stock_movements.quantity_delta)`가 일치해야 합니다. 매 조회마다 합산하지 않고 통합 테스트에서 검증합니다.
