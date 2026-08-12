CREATE TABLE sports_prediction_league_participants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    league_id UUID NOT NULL REFERENCES sports_prediction_leagues (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    points INTEGER NOT NULL DEFAULT 0,
    joined_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (league_id, user_id)
);

CREATE INDEX idx_sports_prediction_league_participants_league_id ON sports_prediction_league_participants (league_id);
CREATE INDEX idx_sports_prediction_league_participants_user_id ON sports_prediction_league_participants (user_id);
