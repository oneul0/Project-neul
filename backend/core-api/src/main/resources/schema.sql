-- DDL for analyzed_chats table
-- roomId = Chzzk channelId

CREATE TABLE IF NOT EXISTS analyzed_chats (
    id            BIGSERIAL PRIMARY KEY,
    message_id    VARCHAR(255) UNIQUE NOT NULL,
    room_id       VARCHAR(255) NOT NULL,  -- Chzzk channelId
    content       TEXT,
    sender        VARCHAR(255),           -- 채팅 작성자 닉네임
    emotion_type  VARCHAR(50),
    emotion_score DOUBLE PRECISION,
    analyzed_at   TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS highlight_records (
    id SERIAL PRIMARY KEY,
    room_id VARCHAR(100) NOT NULL,
    emotion_type VARCHAR(20) NOT NULL,
    peak_score DOUBLE PRECISION NOT NULL,
    top_message TEXT,
    live_image_url TEXT,
    timestamp TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_analyzed_chats_room_id ON analyzed_chats (room_id);
