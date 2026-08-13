CREATE TABLE sports_prediction_fixtures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_sports_fixture_id INTEGER NOT NULL,
    api_sports_league_id INTEGER NOT NULL,
    api_sports_season_id INTEGER NOT NULL,
    home_team_id UUID NOT NULL REFERENCES sports_prediction_teams (id) ON DELETE RESTRICT,
    away_team_id UUID NOT NULL REFERENCES sports_prediction_teams (id) ON DELETE RESTRICT,
    commence_time TIMESTAMP WITH TIME ZONE,
    home_odds DOUBLE PRECISION,
    away_odds DOUBLE PRECISION,
    draw_odds DOUBLE PRECISION,
    status VARCHAR(50),
    home_score INTEGER,
    away_score INTEGER,
    winning_team_id UUID REFERENCES sports_prediction_teams (id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (api_sports_fixture_id, api_sports_league_id, api_sports_season_id)
);

CREATE INDEX idx_sports_prediction_fixtures_api_sports_league_id ON sports_prediction_fixtures (api_sports_league_id);
CREATE INDEX idx_sports_prediction_fixtures_api_sports_season_id ON sports_prediction_fixtures (api_sports_season_id);
CREATE INDEX idx_sports_prediction_fixtures_commence_time ON sports_prediction_fixtures (commence_time);
CREATE INDEX idx_sports_prediction_fixtures_status ON sports_prediction_fixtures (status);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_sports_prediction_fixtures_modtime
BEFORE UPDATE ON sports_prediction_fixtures
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
