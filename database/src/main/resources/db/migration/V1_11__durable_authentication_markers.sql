-- Authentication decisions must not depend on the evictable application cache.
-- Store only hashes, never bearer tokens or client assertions. Timestamps use UTC.
CREATE TABLE security_token_marker (
    marker_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (marker_type, token_hash),
    KEY idx_security_token_marker_expiry (expires_at),
    CONSTRAINT chk_security_token_marker_type
        CHECK (marker_type IN ('REVOKED_ACCESS', 'CLIENT_ASSERTION'))
) ENGINE=InnoDB;
