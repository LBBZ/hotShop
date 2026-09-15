-- Baseline existing inventory at deployment; subsequent business deltas maintain the independent balance.
-- Expression defaults apply only to INSERT, so direct UPDATE tampering remains detectable.
ALTER TABLE catalog_product ADD COLUMN expected_stock BIGINT NOT NULL DEFAULT (stock);
ALTER TABLE flash_sale_activity ADD COLUMN expected_available_stock BIGINT NOT NULL DEFAULT (available_stock);
UPDATE catalog_product SET expected_stock = stock;
UPDATE flash_sale_activity SET expected_available_stock = available_stock;
