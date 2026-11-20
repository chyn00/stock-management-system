package com.example.stock.common.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String REQUEST_ID_CONSTRAINT = "uq_stock_movements_tenant_request";

  @ExceptionHandler(StockApiException.class)
  public ResponseEntity<ErrorResponse> stockError(StockApiException error) {
    return response(error.getErrorCode());
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<ErrorResponse> invalidRequest() {
    return response(ErrorCode.INVALID_REQUEST);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> dataConflict(DataIntegrityViolationException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && REQUEST_ID_CONSTRAINT.equals(violation.getConstraintName())) {
        return response(ErrorCode.REQUEST_CONFLICT);
      }
    }
    throw error;
  }

  private ResponseEntity<ErrorResponse> response(ErrorCode code) {
    return ResponseEntity.status(code.getStatus())
        .body(new ErrorResponse(code.name(), code.getMessage()));
  }
}
