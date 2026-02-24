CREATE TABLE ingredient_units (
    id SERIAL PRIMARY KEY,
    ingredient_id INTEGER REFERENCES ingredients(id) ON DELETE CASCADE,
    
    unit_name VARCHAR(50) NOT NULL,
    gram_weight NUMERIC NOT NULL,
    
    UNIQUE(ingredient_id, unit_name)
);