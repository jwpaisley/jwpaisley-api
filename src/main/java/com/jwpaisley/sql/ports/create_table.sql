CREATE TABLE sailing_ports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    latitude DECIMAL(8, 6) NOT NULL,
    longitude DECIMAL(9, 6) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sailing_port_condition_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sailing_port_id UUID NOT NULL REFERENCES sailing_ports(id) ON DELETE CASCADE,
    wind_speed DOUBLE PRECISION,
    wind_direction DOUBLE PRECISION,
    gust_speed DOUBLE PRECISION,
    current_speed DOUBLE PRECISION,
    current_direction DOUBLE PRECISION,
    wave_height DOUBLE PRECISION,
    wave_period DOUBLE PRECISION,
    water_temperature DOUBLE PRECISION,
    air_temperature DOUBLE PRECISION,
    cloud_cover DOUBLE PRECISION,
    precipitation DOUBLE PRECISION,
    visibility DOUBLE PRECISION,
    weather VARCHAR(40),
    forecast_time TIMESTAMP WITH TIME ZONE,
    fetched_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    raw_response JSONB NOT NULL
);

CREATE INDEX idx_sailing_port_condition_history_port_fetched_at
ON sailing_port_condition_history (sailing_port_id, fetched_at DESC);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE 'plpgsql';

CREATE TRIGGER update_sailing_ports_modtime
BEFORE UPDATE ON sailing_ports
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
