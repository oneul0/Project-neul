CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE vod_highlights
    ADD COLUMN IF NOT EXISTS embedding_text TEXT,
    ADD COLUMN IF NOT EXISTS embedding      vector(768);

CREATE INDEX IF NOT EXISTS idx_vod_highlights_embedding
    ON vod_highlights
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
