ALTER TABLE user_vod_activity
    ADD CONSTRAINT fk_activity_highlight
        FOREIGN KEY (highlight_id)
            REFERENCES vod_highlights(id)
            ON DELETE SET NULL;
