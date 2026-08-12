CREATE TABLE sports_prediction_teams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_sports_team_id INTEGER NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(50),
    logo_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sports_prediction_teams_api_sports_team_id ON sports_prediction_teams (api_sports_team_id);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_sports_prediction_teams_modtime
BEFORE UPDATE ON sports_prediction_teams
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
