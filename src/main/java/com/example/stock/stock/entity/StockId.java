package com.example.stock.stock.entity;

import java.io.Serializable;

public record StockId(long tenantId, long itemId) implements Serializable {}
