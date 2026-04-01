ALTER TABLE vod_highlights
    ADD COLUMN IF NOT EXISTS intensity_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS transition_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS editability_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS reaction_label VARCHAR(100),
    ADD COLUMN IF NOT EXISTS reason_summary TEXT;

UPDATE vod_highlights
SET intensity_score = COALESCE(intensity_score, highlight_score),
    transition_score = COALESCE(transition_score, 0),
    editability_score = COALESCE(editability_score, highlight_score),
    reaction_label = COALESCE(reaction_label, category),
    reason_summary = COALESCE(reason_summary, description)
WHERE intensity_score IS NULL
   OR transition_score IS NULL
   OR editability_score IS NULL
   OR reaction_label IS NULL
   OR reason_summary IS NULL;
