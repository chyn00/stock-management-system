# Stock Management System

- PostgreSQL 기반 상품·재고·재고 변동 이력 관리 시스템
- 구현 범위: 상품 자동 등록을 포함한 입고, 출고, 현재 재고 조회
- 검증 대상: PostgreSQL 트랜잭션, 동시 재고 변경, 현재고와 변동 이력의 정합성

## 해결하려는 문제

- 현재 재고 수량을 빠르게 조회
- 입고·출고·조정이 발생한 근거 보존
- 동시 요청에서도 음수 재고와 변경 이력 누락 방지
- 상품 정보가 부족한 현장 입고를 먼저 처리하고, 사람이 추후 정보 보완

## 핵심 설계와 선택 이유

> 현재 수량의 조회·변경 판단은 `stocks`, 확정된 변경 근거의 보존은 `stock_movements`가 담당합니다. 두 데이터는 하나의 PostgreSQL 트랜잭션에서 함께 커밋합니다.

| 선택 | 해결하려는 문제 | 비용과 한계 |
| --- | --- | --- |
| `items`와 재고 데이터 분리 | 상품 정보 수정과 수량 변경의 책임 구분 | 테이블 간 참조 무결성 필요 |
| `stocks`에 현재 수량 저장 | 조회마다 전체 movement 합계를 계산하는 비용 제거 | 집계와 변동 기록을 함께 갱신해야 함 |
| 조건부 원자 UPDATE | 같은 상품의 동시 출고에서 재고 부족을 DB에서 판단 | 같은 재고 행의 UPDATE는 DB 내부에서 직렬화 |
| `stock_movements`에 INSERT만 허용 | 확정된 변동의 이유와 업무 참조 보존 | 오류 정정은 기존 기록 수정 대신 새 보정 기록 필요 |
| 집계 변경과 movement INSERT를 한 트랜잭션으로 처리 | 수량만 바뀌거나 이력만 남는 부분 성공 방지 | 어느 한쪽이라도 실패하면 전체 롤백 |

- `stocks`는 현재고 조회와 수량 변경의 기준이며, 읽기 전용 테이블이 아님
- 출고는 `quantity >= 요청 수량` 조건을 만족하는 행만 차감하고 성공 여부로 판단
- 별도 `SELECT ... FOR UPDATE` 없이 UPDATE 자체의 행 잠금과 조건 검사 활용
- movement 합계는 매 요청마다 재계산하지 않고 통합 테스트에서 현재고와 일치 여부 검증
- 읽기 모델 분리와 유사한 접근이며, 비동기 CQRS 시스템은 구성하지 않음
- 상세 계약·트랜잭션 경계: [ARCHITECTURE](docs/ARCHITECTURE.md)
- 대안·선택 이유·한계: [DECISIONS](docs/DECISIONS.md)

## 기술 스택

| 구분 | 구성 |
| --- | --- |
| 언어·프레임워크 | Java 21, Spring Boot 4.1.3 |
| 빌드 | Gradle 9.9.2, Kotlin DSL, 단일 모듈 |
| 데이터베이스 | PostgreSQL 17.12, Spring Data JPA, Flyway |
| 테스트 | JUnit Jupiter 5.14.4, Testcontainers 2.0.5 |
| 로컬 환경 | Docker Compose |

- 기본 패키지: `com.example.stock`
- 프로젝트 버전: `0.1.0-SNAPSHOT`
- 의존성: Spring Boot BOM 4.1.3으로 관리, JUnit은 별도 BOM으로 5.14.4 고정

## 로컬 실행

- 준비: Java 21, 실행 중인 Docker, Docker Compose
- 한 번에 실행: DB healthcheck → Flyway 초기화 → JPA 스키마 검증 → HTTP 기동

```bash
./run-local.sh
```

- 스크립트는 빈 HTTP 포트를 자동 선택. 실제 포트는 시작 로그에서 확인
- 고정 포트 실행 예시: `SERVER_PORT=18765 ./run-local.sh`
- 직접 실행: `docker compose up -d --wait postgres` 후 `./gradlew bootRun` (기본 8080)
- DDL 수동 실행 불필요. 제출용 SQL: [V1 migration](src/main/resources/db/migration/V1__create_initial_schema.sql)
- Flyway가 DDL 관리, JPA는 `ddl-auto: validate`로 매핑 검증
- DB 연결 변경: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- 기본 DB 계정·비밀번호: 로컬 개발 전용 예제 값
- MVP 고객사: 실행 설정 `TENANT_ID` (기본 `1`). 요청 헤더로 고객사를 선택하지 않음
- PostgreSQL 중지: `docker compose stop postgres`

## API

- 공통 경로: `/api/v1`
- 상품 ID: 내부 식별자 / SKU: 입고 시 신규·기존 상품을 구분하는 업무 식별자
- SKU: 고객사별 유일, 앞뒤 공백 제거, 대소문자는 구분·유지

### 입고

- `POST /api/v1/stocks/inbound`, 성공 응답 `200`
- 필수 입력: `sku`, 양의 정수 `quantity`, UUID `requestId`
- 선택 입력: `name`, `reason`. 상품명이 없어도 자동 등록 및 입고 가능
- 기존 SKU 입고는 수량만 증가시키며 상품명을 덮어쓰지 않음
- 상품 자동 등록·현재고 증가·movement INSERT를 한 JPA 트랜잭션으로 처리
- 동일 SKU 최초 입고 경쟁은 DB 유일 제약과 `ON CONFLICT DO NOTHING`으로 처리
- 중복 요청 ID는 현재 409로 거절. 동일 요청의 성공 응답 재사용은 후속 멱등성 단계에서 구현

실행 포트를 `18765`로 지정한 경우의 요청 예시:

```bash
curl -X POST http://localhost:18765/api/v1/stocks/inbound \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-001","quantity":10,"requestId":"11111111-1111-1111-1111-111111111111"}'
```

응답 예시:

```json
{"id":1,"name":null,"quantity":10}
```

입력 오류 응답 예시 (`400`):

```json
{"code":"INVALID_REQUEST","message":"잘못된 요청입니다."}
```

### 출고

- `POST /api/v1/stocks/outbound`, 성공 응답 `200`
- 필수 입력: `sku`, 양의 정수 `quantity`, UUID `requestId`
- 선택 입력: `reason`
- 미등록 SKU는 `404`, 재고 부족은 `409`
- 수량 조건을 포함한 원자 UPDATE가 성공한 경우에만 재고 변동 이력을 기록

```bash
curl -X POST http://localhost:18765/api/v1/stocks/outbound \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-001","quantity":3,"requestId":"22222222-2222-2222-2222-222222222222"}'
```

### 현재 재고 조회

- `GET /api/v1/stocks/{itemId}`
- 성공 응답: `id`, `name`, `quantity`
- 미등록 상품 ID는 `404`

```bash
curl http://localhost:18765/api/v1/stocks/1
```

### 요청 오류

- 수량 0 이하, 필수 필드 누락, 잘못된 JSON·UUID는 `400`
- 같은 Tenant의 중복 `requestId`는 `409`

### 전체 요구사항

| 기능·조건 | 처리 계약 |
| --- | --- |
| 입고 | 기존 상품 수량 증가, 미등록 SKU는 상품 등록 후 입고 |
| 동시 최초 입고 | 동일 SKU 상품 중복 생성 방지 |
| 출고 | 기존 상품 수량 감소, 재고 부족 시 409 |
| 현재 재고 조회 | 상품 ID 기준 `id`, `name`, `quantity` 반환. 이름을 모르면 `name: null` |
| 요청 오류 | 수량 0 이하·필수 식별값 누락·형식 오류는 400 |
| 미등록 상품 | 조회·출고는 404. 입고는 자동 등록으로 처리 |
| 요청 ID 충돌 | 동일 요청 ID에 서로 다른 내용이면 409 |

## 핵심 설계 결정

- 이름·특징·가격 등 부가 정보 없이도 SKU와 수량으로 입고 먼저 완료
- 상품명: nullable / 자동 등록 상태: `NEEDS_REVIEW`
- 사람이 정보 보완·확인 후 `REVIEWED`로 전환
- 상품 정보만 보완하면 수량 변경이나 movement 생성 없음
- 사유(`reason`): 정상 입출고는 선택, 외부 조정·시스템 수량 변경·오입출고 보정은 필수
- 수량 보정: 기존 movement를 유지하고 새 증감 기록과 사유 추가
- 미입고·미출고: 미처리 상태 자체를 movement로 기록하지 않음
- 바코드 인식: 상품 식별 단계. 인식만으로 재고 확정하지 않음

## 검증

```bash
./gradlew test
./gradlew build
```

- 애플리케이션 기동: 실제 PostgreSQL 연결, Flyway V1 적용, JPA 매핑
- 입고: 최소 정보 신규 등록, 기존 상품 추가 입고, 동시 최초 입고
- 출고: 정상 출고, 미등록 SKU 404, 재고 부족 409, 동시 출고 음수 방지
- 조회: 상품 ID 기준 `id`, `name`, `quantity` 및 미등록 상품 404
- 요청 검증: 0 이하 수량, 필수값 누락, 잘못된 JSON·UUID 400
- 롤백: movement INSERT 실패 시 집계와 신규 상품 등록 취소
- 혼합 동시성: 동일 상품 입고·출고 요청 동시 처리
- HTTP 응답과 커밋된 현재고·movement 합계 일치 확인
- 테스트 DB: Testcontainers가 생성·정리하는 PostgreSQL. H2 사용 안 함
- JUnit 5 호환: 테스트에서 Spring 애플리케이션을 직접 시작·종료
- 처리량·응답 시간은 미측정. 보정 대상 조회 인덱스는 해당 기능 구현 시 검토

## AI 에이전트와 MCP 활용

| MCP | 사용한 작업 | 사용 이유 |
| --- | --- | --- |
| Serena | 프로젝트 등록과 작업 지침 확인 | 작업 대상과 도구 사용 규칙 확인 |
| Context7 | Testcontainers·Spring/JUnit 호환, JPA bulk UPDATE와 Hibernate 복합 키 문서 조회 | 공식 API와 지원 버전에 맞게 구성하고 DB 동작을 검증 |

- 문서 확인 결과를 실제 Gradle 빌드·PostgreSQL 통합 테스트로 검증
- 사용자와 요구사항·트레이드오프를 검토하고 작업 단위별로 커밋·푸시
- 에이전트 개인 작업 파일은 Git 제외. 공유할 설계와 실행 방법은 프로젝트 문서에 기록

## 개발 방향

- 순서: 환경 → 데이터 모델 → 입고 → 출고 → 조회 → 멱등성·안정성 검증
- 추가 기능은 필수 API와 정합성·동시성 테스트 완료 후 검토
- Tenant별 데이터 관계는 설계에 반영하되, 인증 없는 임의 헤더를 보안 경계로 사용하지 않음
- 인증·신뢰 가능한 Tenant 식별·상품 보정 API는 후속 설계 대상
- 개발 흐름: [WORKFLOW](docs/WORKFLOW.md) / 코드·Git 규칙: [CONVENTIONS](docs/CONVENTIONS.md)
