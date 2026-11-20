package com.example.stock.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
  ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
  INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."),
  STOCK_QUANTITY_OVERFLOW(HttpStatus.CONFLICT, "재고 수량의 저장 한도를 초과했습니다."),
  REQUEST_CONFLICT(HttpStatus.CONFLICT, "이미 사용된 요청 ID입니다.");

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }
}
