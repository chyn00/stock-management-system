CREATE TABLE items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(255),
    barcode VARCHAR(100),
    price NUMERIC(19, 2),
    metadata JSONB,
    review_status VARCHAR(20) NOT NULL DEFAULT 'NEEDS_REVIEW',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_items_tenant_sku UNIQUE (tenant_id, sku),
    CONSTRAINT uq_items_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_items_sku_not_blank CHECK (btrim(sku) <> ''),
    CONSTRAINT ck_items_name_not_blank CHECK (name IS NULL OR btrim(name) <> ''),
    CONSTRAINT ck_items_price_nonnegative CHECK (price >= 0),
    CONSTRAINT ck_items_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_items_review_status
        CHECK (review_status IN ('NEEDS_REVIEW', 'REVIEWED'))
);

CREATE TABLE stocks (
    tenant_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    quantity BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, item_id),
    CONSTRAINT fk_stocks_item FOREIGN KEY (tenant_id, item_id)
        REFERENCES items (tenant_id, id),
    CONSTRAINT ck_stocks_quantity_nonnegative CHECK (quantity >= 0)
);

CREATE TABLE stock_movements (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    quantity_delta BIGINT NOT NULL,
    request_id UUID NOT NULL,
    reference_type VARCHAR(100),
    reference_id VARCHAR(255),
    reason TEXT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_stock_movements_stock FOREIGN KEY (tenant_id, item_id)
        REFERENCES stocks (tenant_id, item_id),
    CONSTRAINT uq_stock_movements_tenant_request UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_stock_movements_event_type
        CHECK (event_type IN ('INBOUND', 'OUTBOUND', 'ADJUSTMENT')),
    CONSTRAINT ck_stock_movements_source_type
        CHECK (source_type IN ('API', 'EXTERNAL', 'SYSTEM')),
    CONSTRAINT ck_stock_movements_delta_nonzero CHECK (quantity_delta <> 0),
    CONSTRAINT ck_stock_movements_delta_direction CHECK (
        (event_type = 'INBOUND' AND quantity_delta > 0)
        OR (event_type = 'OUTBOUND' AND quantity_delta < 0)
        OR event_type = 'ADJUSTMENT'
    ),
    CONSTRAINT ck_stock_movements_reason_required CHECK (
        (event_type <> 'ADJUSTMENT' AND source_type <> 'SYSTEM')
        OR (reason IS NOT NULL AND btrim(reason) <> '')
    )
);

CREATE INDEX idx_stock_movements_tenant_item
    ON stock_movements (tenant_id, item_id);

CREATE FUNCTION reject_stock_movement_changes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'stock_movements is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER stock_movements_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON stock_movements
    FOR EACH STATEMENT EXECUTE FUNCTION reject_stock_movement_changes();
