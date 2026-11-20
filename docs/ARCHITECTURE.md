# 아키텍처

## 초기 테이블 계약

업무 테이블은 `items`, `stocks`, `stock_movements` 세 개이며, DDL은 `V1__create_initial_schema.sql`로 관리합니다. Flyway의 `flyway_schema_history`는 migration 관리 테이블입니다.

업무 데이터 접근은 Spring Data JPA로 구현합니다. Flyway가 애플리케이션 시작 시 최초 DDL을 적용하고, JPA는 `ddl-auto: validate`로 매핑을 검증합니다. `open-in-view`는 끄고 Service 안에서 필요한 값을 응답 DTO로 만듭니다. JdbcTemplate은 커밋 결과를 확인하는 테스트에서만 사용합니다.

패키지는 테이블별이 아니라 업무 도메인별로 나누고 그 안에 레이어를 둡니다. `stock`은 `api/service/repository/entity`로 구성합니다. Controller는 `api`, 요청·응답 DTO는 `api/dto`에 둡니다. 변동 기록도 재고 업무의 일부이므로 `StockMovement`와 그 Repository는 `stock` 안에 둡니다. 상품 정보는 `item`로 분리하며 현재 필요한 `entity/repository`만 둡니다. 빈 패키지나 공통 기반 엔티티는 만들지 않습니다.

- `items`: BIGINT 내부 ID와 고객사 ID, 고객사별 유일한 SKU, nullable 상품명·바코드·가격·JSONB 메타데이터, 확인 상태, 생성 시각을 관리합니다. SKU는 대소문자를 구분하며 자동 변환하지 않습니다. 가격은 선택 값이며 입력하면 0 이상이어야 합니다.
- `stocks`: 상품별 현재 수량을 저장하며 실시간 조회에 사용합니다. 조건부 UPDATE 등 PostgreSQL의 원자적 연산으로 재고 부족 및 동시 변경을 제어합니다.
- `stock_movements`: 입고, 출고, 관리자 조정 등 확정된 재고 변동을 `event_type`, `source_type`, `quantity_delta`, 요청 식별자, 업무 원본 참조, 사유(`reason`), 기록 시각과 함께 append-only로 보존합니다. `event_type`과 `source_type`은 별도 값이며, 사유는 업무 원본 참조와 구분합니다.

수량과 증감은 BIGINT 정수로 관리합니다. `stocks`의 `(tenant_id, item_id)`가 기본 키이며 수량 기본값은 0입니다. JPA는 이 복합키를 `Stock` 내부의 `Key` record로 표현합니다. 별도 엔티티나 추가 계층은 두지 않습니다. 변동 유형은 `INBOUND`, `OUTBOUND`, `ADJUSTMENT`, 출처는 `API`, `EXTERNAL`, `SYSTEM`입니다. 입고 delta는 양수, 출고 delta는 음수이며 조정도 0은 허용하지 않습니다. 오입출고 보정은 `ADJUSTMENT`로 기록합니다. 업무 원본은 선택적인 `reference_type`, `reference_id`로 참조하고, 기록 시각은 TIMESTAMPTZ로 저장합니다.

## 변동 기록과 집계

- movements에 허용되는 작업은 INSERT뿐입니다. 기존 기록의 UPDATE와 DELETE를 금지합니다.
- DB의 문장 단위 트리거가 UPDATE·DELETE·TRUNCATE를 거절합니다. 수량 집계나 업무 흐름은 이 트리거에서 처리하지 않고 Service 트랜잭션에서 담당합니다.
- 변경 전·후 수량은 저장하지 않고 `quantity_delta`만 기록합니다. 잘못된 이력은 별도의 보정 이력으로 정정합니다.
- movements에는 확정된 재고 변동만 기록합니다. 원본 업무 데이터 없이 미입고·미출고 상태를 movement만으로 추론하지 않습니다.
- 초기 재고 0을 기준으로 커밋된 `stocks.quantity`는 `SUM(stock_movements.quantity_delta)`와 일치해야 하며, 재고는 음수가 될 수 없습니다. 이 합계를 매 요청마다 재계산하지 않고 통합 테스트에서 정합성을 검증합니다.
- movement 합계에 의존하지 않고 빠른 현재 재고 조회를 위해 집계 테이블을 둡니다. CQRS와 유사한 읽기 모델 분리지만 비동기 독립 시스템은 아닙니다.

## 트랜잭션과 동시성

`stocks`는 조회용 집계와 수량 변경 판단을 함께 담당합니다. 같은 상품의 재고 요청이 이 행의 원자 UPDATE를 거치도록 해 재고 부족 여부를 한 곳에서 판단합니다. 별도의 잠금용 테이블이나 컬럼은 두지 않습니다. `stock_movements`는 확정된 변경 근거를 누적하는 역할입니다.

재고 변경은 한 PostgreSQL 트랜잭션에서 먼저 조건부 원자 UPDATE로 `stocks.quantity`를 검증·갱신한 뒤 `stock_movements`에 INSERT합니다. 조건부 UPDATE가 성공해야 movement를 기록하며, INSERT나 후속 처리에 실패하면 집계 갱신도 롤백합니다. 순서를 바꾸더라도 동일 트랜잭션 롤백으로 정합성을 지킬 수 있지만, 집계 갱신 성공 여부가 재고 허용 여부를 결정하므로 집계를 먼저 갱신하는 흐름을 기본안으로 둡니다.

조건부 UPDATE는 `quantity + delta >= 0`을 데이터베이스에서 원자적으로 검사하므로 애플리케이션이 별도로 `SELECT ... FOR UPDATE`를 수행할 필요는 없습니다. PostgreSQL은 같은 행에 대한 동시 UPDATE를 내부적으로 직렬화하고 조건을 다시 검사합니다. 따라서 애플리케이션 수준의 명시적 비관 잠금은 추가하지 않습니다. PostgreSQL 동시성 통합 테스트에서 동시 최초 입고와 수량 반영을 검증합니다.

요청 ID는 UUID이며 `(tenant_id, request_id)` 유일 제약으로 같은 고객사의 중복 기록을 막습니다. 요청 내용과 관계없이 중복 ID는 `409 REQUEST_CONFLICT`로 거절합니다. 같은 성공 응답을 돌려주는 재시도 멱등성은 구현하지 않았습니다.

입고 API는 먼저 고객사와 SKU로 상품을 조회합니다. 이미 있으면 해당 상품의 재고를 반영하고, 없을 때만 `ON CONFLICT DO NOTHING`으로 등록을 시도한 뒤 SKU로 다시 조회합니다. 두 요청이 동시에 상품을 찾지 못해도 유일 제약으로 상품은 하나만 생성됩니다. 상품 조회·등록, `stocks` 증가, movement 기록은 같은 트랜잭션에서 처리합니다. 기존 엔티티를 읽어 수량을 더한 후 save하는 방식은 사용하지 않고, JPA bulk UPDATE로 재고를 증가시킵니다. UPDATE 전에 `Stock` 엔티티를 읽지 않고, 응답은 갱신 뒤 다시 조회해 최신 수량을 사용합니다. INSERT 제약 위반은 트랜잭션 전체를 롤백하며 중복 요청 ID는 409로 응답합니다.

## Tenant 경계

`stocks`는 `(tenant_id, item_id)`로 `items(tenant_id, id)`를 참조하고, movement는 같은 복합 키로 `stocks`를 참조합니다. 따라서 다른 고객사의 상품 ID를 잘못 연결하는 변경은 FK가 거절합니다. 고객사별 SKU 유일성과 FK 무결성은 DB가 보장하지만 인증·접근 권한을 대신하지 않습니다. 인증 없는 MVP에서 임의의 `tenant_id` 헤더는 신뢰 경계가 아닙니다. 실제 Tenant 식별과 권한 부여는 후속 설계 대상입니다.

현재 입고 API는 실행 설정 `stock.tenant-id`의 단일 고객사를 사용합니다. 클라이언트가 임의의 Tenant 헤더를 보내 고객사를 변경할 수 있게 하지 않습니다. 실제 다중 고객사 인증 기능을 제공하는 것은 아닙니다.

## 상품과 바코드

상품 ID는 내부 식별자이고 SKU는 업무 식별자입니다. SKU는 같은 고객사 안에서 유일하고 대소문자를 구분합니다. 미등록 SKU 입고는 상품 등록과 재고 확정을 함께 진행합니다. 부가 메타데이터가 없어도 입고를 막지 않습니다. 모르는 상품명은 DB와 응답에서 null로 표현하며 임시 문구로 대체하지 않습니다. 바코드 인식만으로 재고를 확정하지 않습니다.

상품 정보 확인 상태는 `review_status`의 두 값으로 관리합니다. 자동 등록은 `NEEDS_REVIEW`, 사람이 정보를 보완하고 확인한 뒤에는 `REVIEWED`입니다. 이름이 있어도 사람이 확인하지 않았다면 `NEEDS_REVIEW`일 수 있습니다. 이 상태는 상품 메타데이터의 확인 여부이며 입고·출고를 막는 재고 상태가 아닙니다. 상품명·특징 등 메타데이터만 보완할 때는 수량 변경이나 movement INSERT가 발생하지 않습니다.

현재 DDL에는 상품·Tenant 키와 `stock_movements(tenant_id, item_id)` 인덱스가 있습니다. EXPLAIN 기반 실행 계획과 성능 수치는 아직 확인하지 않았습니다. 상품 보정 조회 등 실제 조회 조건과 데이터 분포가 구체화되면 인덱스 추가 여부를 검토합니다.

## 변경 사유

`reason`은 모든 movement에서 입력할 수 있는 설명입니다. `event_type`, `source_type`, 원본 업무 참조와 별도로 저장합니다.

| 변경 상황 | 사유 필수 여부 |
| --- | --- |
| 정상 입고·출고 | 선택 |
| 외부 조정·시스템 수량 변경 | 필수 |
| 오입고·오출고 보정 | 필수 |

오입고·오출고 보정은 기존 movement를 유지하고 필요한 증감과 사유를 담은 새 movement를 추가합니다. 보정에도 같은 재고 음수 방지 및 트랜잭션 규칙을 적용합니다. 미입고·미출고처럼 아직 확정되지 않은 상태는 수량 변동을 나타내지 않으므로 movement를 생성하지 않습니다.

## 무결성 및 API 요구사항

- DB 제약조건과 트리거로 음수 수량, 잘못된 참조, movement UPDATE/DELETE/TRUNCATE를 거절합니다. 실제 집계 갱신과 movement INSERT의 쌍은 Service 트랜잭션과 PostgreSQL 통합 테스트로 보장합니다.
- REST API 기본 경로는 `/api`입니다. 상품 ID 기준 조회 응답은 `id`, `name`, `quantity`를 제공합니다.
- 잘못된 수량(0 이하), 필수 식별 필드 누락, 요청 변환 실패와 잘못된 조회 ID는 400, 지원하지 않는 `Content-Type`은 415, 미등록 상품 조회·출고는 404, 재고 부족·수량 범위 초과·중복 요청 ID는 409로 처리합니다. 중복 요청 ID는 payload 비교 없이 거절합니다.
- 현재 입고·출고 API의 `source_type`은 `API`이며 `reason`은 선택 입력입니다. DDL은 향후 `ADJUSTMENT` 또는 `SYSTEM` 변동에 사유를 요구하지만 해당 처리 API는 아직 없습니다. 향후 API를 추가할 때 애플리케이션에서 사유를 검증하고 적절한 400 응답을 반환해야 합니다.

## 범위와 검증 상태

초기 범위는 단일 Spring Boot 애플리케이션과 PostgreSQL 트랜잭션입니다. 물류센터와 예약 재고 같은 추가 업무 모델은 포함하지 않습니다.

애플리케이션 기동 테스트에서 Flyway V1 적용과 JPA 매핑을 확인합니다. 기능·동시성·롤백·정합성 테스트는 PostgreSQL까지 수행합니다. EXPLAIN 기반 실행 계획과 성능 수치는 아직 확인하지 않았으며, 대표 조회 조건과 데이터 규모가 정해지면 점검합니다.

핵심 API는 실제 HTTP 요청부터 JPA와 PostgreSQL까지 검증합니다. 신규·기존 입고, 출고, 현재고 조회, 잘못된 입력, 재고 부족, 동시 최초 입고·동시 입출고, movement INSERT 실패 롤백 및 현재고와 변동 합계 일치를 테스트합니다.
