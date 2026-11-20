package com.example.stock.stock.service;

import com.example.stock.common.exception.ErrorCode;
import com.example.stock.common.exception.StockApiException;
import com.example.stock.stock.api.dto.StockResponse;
import com.example.stock.stock.repository.StockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StockQueryService {

  private final StockRepository stockRepository;
  private final long tenantId;

  public StockQueryService(
      StockRepository stockRepository,
      @Value("${stock.tenant-id}") long tenantId) {
    this.stockRepository = stockRepository;
    this.tenantId = tenantId;
  }

  public StockResponse getStock(long itemId) {
    return stockRepository
        .findStockResponse(tenantId, itemId)
        .orElseThrow(() -> new StockApiException(ErrorCode.ITEM_NOT_FOUND));
  }
}
