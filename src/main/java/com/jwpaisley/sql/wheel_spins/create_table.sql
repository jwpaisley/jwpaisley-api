CREATE TABLE wheel_spins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    outcome INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_wheel_spins_user_id ON wheel_spins (user_id);
CREATE INDEX idx_wheel_spins_created_at ON wheel_spins (created_at);

CREATE TRIGGER update_wheel_spins_modtime
BEFORE UPDATE ON wheel_spins
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
