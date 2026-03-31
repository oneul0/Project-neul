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

CREATE INDEX IF NOT EXISTS idx_vod_timeline_points_video_no
ON vod_timeline_points (video_no);
