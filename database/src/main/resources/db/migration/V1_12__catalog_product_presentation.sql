-- Optional product media and specifications; existing catalog entries remain valid.
ALTER TABLE catalog_product ADD COLUMN presentation_json JSON NULL;
