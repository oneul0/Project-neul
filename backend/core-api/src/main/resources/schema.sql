-- DDL for analyzed_chats table

CREATE TABLE IF NOT EXISTS analyzed_chats (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(255) UNIQUE NOT NULL,
    room_id VARCHAR(255) NOT NULL,
    content TEXT,
    emotion_type VARCHAR(50),
    emotion_score DOUBLE PRECISION,
    analyzed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_analyzed_chats_room_id ON analyzed_chats (room_id);
