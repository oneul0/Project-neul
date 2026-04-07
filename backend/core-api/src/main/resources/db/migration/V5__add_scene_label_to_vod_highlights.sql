ALTER TABLE vod_highlights
    ADD COLUMN IF NOT EXISTS scene_label VARCHAR(120);

UPDATE vod_highlights
SET scene_label = COALESCE(scene_label, reaction_label, category)
WHERE scene_label IS NULL;
