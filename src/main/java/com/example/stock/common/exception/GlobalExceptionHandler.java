package com.example.stock.common.exception;

import java.sql.SQLException;
import org.hibernate.JDBCException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String REQUEST_ID_CONSTRAINT = "uq_stock_movements_tenant_request";
  private static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";

  @ExceptionHandler(StockApiException.class)
  public ResponseEntity<ErrorResponse> stockError(StockApiException error) {
    return response(error.getErrorCode());
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<ErrorResponse> invalidRequest() {
    return response(ErrorCode.INVALID_REQUEST);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> invalidPathParameter() {
    return response(ErrorCode.INVALID_REQUEST);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> unsupportedMediaType() {
    return response(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> dataConflict(DataIntegrityViolationException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && REQUEST_ID_CONSTRAINT.equals(violation.getConstraintName())) {
        return response(ErrorCode.REQUEST_CONFLICT);
      }
      if (isNumericValueOutOfRange(cause)) {
        return response(ErrorCode.STOCK_QUANTITY_OVERFLOW);
      }
    }
    throw error;
  }

  private static boolean isNumericValueOutOfRange(Throwable cause) {
    if (cause instanceof JDBCException jdbcException
        && NUMERIC_VALUE_OUT_OF_RANGE.equals(jdbcException.getSQLState())) {
      return true;
    }
    return cause instanceof SQLException sqlException
        && NUMERIC_VALUE_OUT_OF_RANGE.equals(sqlException.getSQLState());
  }

  private ResponseEntity<ErrorResponse> response(ErrorCode code) {
    return ResponseEntity.status(code.getStatus())
        .body(new ErrorResponse(code.name(), code.getMessage()));
  }
}
