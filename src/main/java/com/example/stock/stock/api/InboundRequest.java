package com.example.stock.stock.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record InboundRequest(
    @NotBlank @Size(max = 100) String sku,
    @Size(max = 255) String name,
    @Positive long quantity,
    @NotNull UUID requestId,
    String reason) {

  public InboundRequest {
    sku = sku == null ? null : sku.strip();
    name = name == null || name.isBlank() ? null : name.strip();
  }
}
