CREATE TABLE sports_prediction_picks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    league_id UUID NOT NULL REFERENCES sports_prediction_leagues (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    fixture_id UUID NOT NULL REFERENCES sports_prediction_fixtures (id) ON DELETE CASCADE,
    selected_team_id UUID REFERENCES sports_prediction_teams (id) ON DELETE RESTRICT,
    is_draw_pick BOOLEAN NOT NULL DEFAULT FALSE,
    is_correct BOOLEAN NOT NULL DEFAULT FALSE,
    is_settled BOOLEAN NOT NULL DEFAULT FALSE,
    payout_multiplier NUMERIC(6, 2) NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    coins_awarded INTEGER NOT NULL DEFAULT 0,
    points_awarded INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (league_id, user_id, fixture_id)
);

CREATE INDEX idx_sports_prediction_picks_league_id ON sports_prediction_picks (league_id);
CREATE INDEX idx_sports_prediction_picks_user_id ON sports_prediction_picks (user_id);
CREATE INDEX idx_sports_prediction_picks_fixture_id ON sports_prediction_picks (fixture_id);
CREATE INDEX idx_sports_prediction_picks_status ON sports_prediction_picks (status);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_sports_prediction_picks_modtime
BEFORE UPDATE ON sports_prediction_picks
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
