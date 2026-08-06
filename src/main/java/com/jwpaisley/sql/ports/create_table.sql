CREATE TABLE sailing_ports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    latitude DECIMAL(8, 6) NOT NULL,
    longitude DECIMAL(9, 6) NOT NULL,
    tide_station_id VARCHAR(20),
    current_station_id VARCHAR(20),
    buoy_station_id VARCHAR(20),
    nws_office VARCHAR(10),
    nws_grid_x INTEGER,
    nws_grid_y INTEGER,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

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
