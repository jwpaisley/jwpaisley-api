CREATE TABLE wheel_options (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    value INTEGER NOT NULL,
    probability DECIMAL(10, 6) NOT NULL CHECK (probability > 0),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_wheel_options_value ON wheel_options (value);

CREATE TRIGGER update_wheel_options_modtime
BEFORE UPDATE ON wheel_options
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

INSERT INTO wheel_options (value, probability)
VALUES
    (1, 0.050000),
    (5, 0.150000),
    (10, 0.150000),
    (25, 0.250000),
    (50, 0.200000),
    (100, 0.150000),
    (250, 0.100000),
    (500, 0.040000),
    (1000, 0.010000);
