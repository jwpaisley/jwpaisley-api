CREATE TABLE sailboat_photos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sailboat_id UUID NOT NULL,
    voyage_id UUID,
    photo_url VARCHAR(2000),
    show_in_carousel BOOLEAN NOT NULL DEFAULT FALSE,
    caption TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sailboat_photo_sailboat
        FOREIGN KEY (sailboat_id) REFERENCES sailboats(id) ON DELETE CASCADE
);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE 'plpgsql';

CREATE TRIGGER update_sailboat_photos_modtime
BEFORE UPDATE ON sailboat_photos
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
