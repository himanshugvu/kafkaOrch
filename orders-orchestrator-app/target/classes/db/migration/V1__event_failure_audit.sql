CREATE TABLE IF NOT EXISTS event_failures (
  id BIGSERIAL PRIMARY KEY,
  source_topic   VARCHAR(255) NOT NULL,
  target_topic   VARCHAR(255) NOT NULL,
  partition      INT NOT NULL,
  "offset"       BIGINT NOT NULL,
  message_key    TEXT,
  headers_text   TEXT,
  payload_bytes  BYTEA,      -- prefer BYTES for speed if you must store payloads
  payload_text   TEXT,       -- optional human-readable storage
  status         VARCHAR(32) NOT NULL,  -- RECEIVED | SUCCESS | FAILED
  error_message  TEXT,
  error_stack    TEXT,
  dedup_key      VARCHAR(512),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_event_failures_status ON event_failures(status);
CREATE UNIQUE INDEX IF NOT EXISTS uidx_event_failures_dedup ON event_failures(dedup_key);
