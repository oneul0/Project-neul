CREATE TABLE IF NOT EXISTS user_vod_library (
    id BIGSERIAL PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    video_no VARCHAR(255) NOT NULL,
    status VARCHAR(100),
    last_viewed_at TIMESTAMP,
    last_analyzed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_vod_library_owner_video UNIQUE (owner_id, video_no)
);

CREATE INDEX IF NOT EXISTS idx_user_vod_library_owner_id
ON user_vod_library (owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS user_vod_activity (
    id BIGSERIAL PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    video_no VARCHAR(255) NOT NULL,
    highlight_id BIGINT,
    action_type VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_vod_activity_owner_id
ON user_vod_activity (owner_id, created_at DESC);
