# API 실행 예시

애플리케이션을 실행한 뒤 아래 명령을 터미널에서 실행합니다.
응답 상태와 JSON 본문을 함께 확인할 수 있습니다.

```bash
set -e
API_BASE=http://localhost:18765/api
```

## 잘못된 요청 · 400

```bash
curl -i -X POST "$API_BASE/stocks/inbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"INVALID-$(date +%s)-$$\",\"quantity\":0,\"requestId\":\"$(uuidgen)\"}"
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{"code":"INVALID_REQUEST","message":"잘못된 요청입니다."}
```

## 미등록 상품 출고 · 404

```bash
curl -i -X POST "$API_BASE/stocks/outbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"UNKNOWN-$(date +%s)-$$\",\"quantity\":1,\"requestId\":\"$(uuidgen)\"}"
```

```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{"code":"ITEM_NOT_FOUND","message":"상품을 찾을 수 없습니다."}
```

## 재고 부족 출고 · 409

```bash
SKU="LOW-STOCK-$(date +%s)-$$"

curl -fsS -X POST "$API_BASE/stocks/inbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$SKU\",\"quantity\":1,\"requestId\":\"$(uuidgen)\"}"

curl -i -X POST "$API_BASE/stocks/outbound" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$SKU\",\"quantity\":2,\"requestId\":\"$(uuidgen)\"}"
```

```http
HTTP/1.1 409 Conflict
Content-Type: application/json

{"code":"INSUFFICIENT_STOCK","message":"재고가 부족합니다."}
```
