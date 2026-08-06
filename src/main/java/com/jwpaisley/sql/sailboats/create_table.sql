CREATE TABLE sailboats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    mmsi VARCHAR(20),
    hin_cin VARCHAR(20),
    official_number VARCHAR(50),
    flag_state VARCHAR(50),
    call_sign VARCHAR(20),
    make VARCHAR(255),
    manufacturer VARCHAR(255),
    model VARCHAR(255),
    year_built INTEGER,
    designer VARCHAR(255),
    hull_type VARCHAR(100),
    hull_material VARCHAR(100),
    keel_type VARCHAR(100),
    rig_type VARCHAR(100),
    loa NUMERIC(8, 2),
    lwl NUMERIC(8, 2),
    beam_ft NUMERIC(6, 2),
    draft_min NUMERIC(6, 2),
    draft_max NUMERIC(6, 2),
    displacement_weight NUMERIC(10, 2),
    ballast_weight NUMERIC(10, 2),
    sail_area NUMERIC(10, 2),
    phrf_rating VARCHAR(50),
    orc_rating VARCHAR(50),
    engine_make_model VARCHAR(255),
    engine_hp INTEGER,
    fuel_capacity_gal NUMERIC(8, 2),
    freshwater_capacity_gal NUMERIC(8, 2),
    holding_tank_capacity_gal NUMERIC(8, 2),
    home_port VARCHAR(255),
    private_ensign_flag_url VARCHAR(1000) DEFAULT 'https://storage.googleapis.com/jwpaisley-sailboat-private-ensign-flags/blank.png',
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

CREATE TRIGGER update_sailboats_modtime
BEFORE UPDATE ON sailboats
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
