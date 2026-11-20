package com.example.stock.stock.repository;

import com.example.stock.stock.entity.StockMovement;
import org.springframework.data.repository.Repository;

public interface StockMovementRepository extends Repository<StockMovement, Long> {

  StockMovement save(StockMovement movement);
}
