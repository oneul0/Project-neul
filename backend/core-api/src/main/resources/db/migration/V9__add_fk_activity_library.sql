ALTER TABLE user_vod_activity
    ADD CONSTRAINT fk_activity_library
        FOREIGN KEY (owner_id, video_no)
            REFERENCES user_vod_library(owner_id, video_no)
            ON DELETE CASCADE;
