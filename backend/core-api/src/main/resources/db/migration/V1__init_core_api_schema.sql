CREATE TABLE IF NOT EXISTS analyzed_chats (
    id            BIGSERIAL PRIMARY KEY,
    message_id    VARCHAR(255) UNIQUE NOT NULL,
    room_id       VARCHAR(255) NOT NULL,
    content       TEXT,
    sender        VARCHAR(255),
    sender_id     VARCHAR(255),
    emotion_type  VARCHAR(50),
    emotion_score DOUBLE PRECISION,
    analyzed_at   TIMESTAMP NOT NULL
);

ALTER TABLE analyzed_chats ADD COLUMN IF NOT EXISTS sender_id VARCHAR(255);

CREATE TABLE IF NOT EXISTS highlight_records (
    id SERIAL PRIMARY KEY,
    room_id VARCHAR(100) NOT NULL,
    emotion_type VARCHAR(20) NOT NULL,
    peak_score DOUBLE PRECISION NOT NULL,
    top_message TEXT,
    live_image_url TEXT,
    timestamp TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS vod_highlights (
    id              BIGSERIAL PRIMARY KEY,
    video_no        VARCHAR(255) NOT NULL,
    start_seconds   INTEGER NOT NULL,
    end_seconds     INTEGER NOT NULL,
    highlight_score DOUBLE PRECISION NOT NULL,
    category        VARCHAR(100),
    description     TEXT,
    top_message     TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_analyzed_chats_room_id ON analyzed_chats (room_id);
CREATE INDEX IF NOT EXISTS idx_vod_highlights_video_no ON vod_highlights (video_no);
