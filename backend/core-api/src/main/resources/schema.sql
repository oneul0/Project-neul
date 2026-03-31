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

-- Ensure sender_id column exists for older versions of the table
ALTER TABLE analyzed_chats ADD COLUMN IF NOT EXISTS sender_id VARCHAR(255);

-- DDL for highlight_records table
CREATE TABLE IF NOT EXISTS highlight_records (
    id SERIAL PRIMARY KEY,
    room_id VARCHAR(100) NOT NULL,
    emotion_type VARCHAR(20) NOT NULL,
    peak_score DOUBLE PRECISION NOT NULL,
    top_message TEXT,
    live_image_url TEXT,
    timestamp TIMESTAMP NOT NULL
);

-- DDL for vod_highlights table
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

CREATE TABLE IF NOT EXISTS vod_timeline_points (
    id                BIGSERIAL PRIMARY KEY,
    video_no          VARCHAR(255) NOT NULL,
    start_seconds     INTEGER NOT NULL,
    end_seconds       INTEGER NOT NULL,
    message_count     INTEGER NOT NULL,
    participant_count INTEGER NOT NULL,
    activity_score    DOUBLE PRECISION NOT NULL,
    category          VARCHAR(100),
    top_message       TEXT,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indices
CREATE INDEX IF NOT EXISTS idx_analyzed_chats_room_id ON analyzed_chats (room_id);
CREATE INDEX IF NOT EXISTS idx_vod_highlights_video_no ON vod_highlights (video_no);
CREATE INDEX IF NOT EXISTS idx_vod_timeline_points_video_no ON vod_timeline_points (video_no);
