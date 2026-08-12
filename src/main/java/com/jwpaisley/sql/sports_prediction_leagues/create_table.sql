CREATE TYPE sports_prediction_sport AS ENUM (
    'SOCCER',
    'FOOTBALL',
    'HOCKEY',
    'BASEBALL',
    'BASKETBALL',
    'F1'
);

CREATE TABLE sports_prediction_leagues (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    image_url TEXT,
    description TEXT,
    sports_prediction_sport sport NOT NULL,
    is_private BOOLEAN NOT NULL DEFAULT FALSE,
    first_place_prize_coins INTEGER NOT NULL DEFAULT 0,
    second_place_prize_coins INTEGER NOT NULL DEFAULT 0,
    third_place_prize_coins INTEGER NOT NULL DEFAULT 0,
    api_sports_league_id INTEGER NOT NULL UNIQUE,
    api_sports_season_id INTEGER NOT NULL,
    league_image_url TEXT,
    league_start_date TIMESTAMP WITH TIME ZONE,
    league_end_date TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sports_prediction_leagues_api_sports_league_id ON sports_prediction_leagues (api_sports_league_id);
CREATE INDEX idx_sports_prediction_leagues_name ON sports_prediction_leagues (name);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_sports_prediction_leagues_modtime
BEFORE UPDATE ON sports_prediction_leagues
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
