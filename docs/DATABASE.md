# 데이터베이스 설계

핵심 테이블은 `items`, `stocks`, `stock_movements` 세 개입니다. 상품 정보, 현재 수량, 수량 변경 근거를 각각 담당합니다.

## items

| 컬럼 | 설계 이유 |
| --- | --- |
| `id` | API와 다른 테이블이 사용하는 내부 상품 식별자 |
| `tenant_id`, `sku` | SKU를 고객사 범위에서 식별하고 `(tenant_id, sku)` 유일 제약으로 동시 최초 등록을 처리 |
| `name` | 입고 시점에 상품명을 몰라도 등록할 수 있도록 nullable |
| `barcode`, `price`, `metadata` | 입고 확정에 필요하지 않은 상품 정보는 선택 값 |
| `review_status` | 자동 등록된 상품 정보를 나중에 확인할 수 있도록 상태를 보존 |

## stocks

| 컬럼 | 설계 이유 |
| --- | --- |
| `tenant_id`, `item_id` | 복합 기본키로 고객사별 상품 재고를 식별하고 `items`를 복합 FK로 참조. 별도 `stock_id`는 두지 않음 |
| `quantity` | 조회용 현재 수량 집계. 0에서 시작하고 조건부 원자 UPDATE로 갱신 |

`CHECK (quantity >= 0)`으로 음수 재고를 막습니다. 초기 재고가 0이므로 현재 수량은 movement `quantity_delta` 합계와 일치해야 합니다. 합계는 매 요청마다 계산하지 않고 통합 테스트에서 확인합니다.

## stock_movements

| 컬럼 | 설계 이유 |
| --- | --- |
| `event_type` | 입고(`INBOUND`), 출고(`OUTBOUND`), 조정(`ADJUSTMENT`) 중 어떤 재고 변화인지 구분 |
| `source_type` | 재고 변경이 들어온 경로를 기록. 현재 입고·출고 API 요청은 `API`로 남기며, 실제 운영에서 외부 시스템 연동이나 내부 시스템 작업이 재고를 바꾸는 경우를 구분하도록 `EXTERNAL`, `SYSTEM`도 둠 |
| `quantity_delta` | 변경 전후 전체 수량 대신 확정된 증감만 기록. 입고는 양수, 출고는 음수 |
| `request_id` | `(tenant_id, request_id)` 유일 제약으로 중복 기록을 감지. 중복 요청은 `409`로 응답 |
| `reason` | 보정 API, 외부 시스템, 내부 관리자가 수량을 수정하거나 미출·오입고를 바로잡을 때 사유를 남겨 변경 이력을 추적. `ADJUSTMENT` 또는 `SYSTEM` 변경에는 필수 |
| `reference_type`, `reference_id` | 변동을 발생시킨 원본 업무 데이터의 선택 참조 |
| `recorded_at` | movement가 기록된 시각 |

현재 구현된 입고·출고 API는 `source_type=API`로 기록합니다. 외부 연동과 시스템 작업 경로는 해당 처리 기능이 추가될 때 사용합니다.

## 관계와 기록 보존

- `stocks(tenant_id, item_id)`는 같은 고객사의 `items`를 복합 FK로 참조합니다.
- `stock_movements(tenant_id, item_id)`는 `stocks`를 복합 FK로 참조합니다.
- 입고·출고는 `stocks` 갱신과 movement INSERT를 한 PostgreSQL 트랜잭션으로 처리합니다. 한쪽이 실패하면 전체를 롤백합니다.
- 확정된 movement는 수정·삭제하지 않습니다. DB 트리거가 `UPDATE`, `DELETE`, `TRUNCATE`를 거절합니다. 정정은 기존 기록을 바꾸지 않고 새 보정 movement로 남기는 것이 설계 원칙입니다.
