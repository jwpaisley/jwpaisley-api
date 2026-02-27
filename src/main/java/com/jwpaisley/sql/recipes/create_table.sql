CREATE TABLE recipes (
    id SERIAL PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    name VARCHAR(255) NOT NULL,
    description TEXT,
    emoji VARCHAR(10) NOT NULL,

    calories INTEGER,
    protein INTEGER,
    fat INTEGER,
    carbohydrates INTEGER,
    sugar INTEGER,
    fiber INTEGER,
    sodium INTEGER,

    ingredients TEXT[],
    mise_en_place_steps TEXT[],
    instructions TEXT[]
);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_recipes_modtime
BEFORE UPDATE ON recipes
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();