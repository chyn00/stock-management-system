package com.example.stock.common.exception;

public class StockApiException extends RuntimeException {

  private final ErrorCode errorCode;

  public StockApiException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
