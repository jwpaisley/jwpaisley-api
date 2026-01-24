CREATE TABLE ingredient_category_mapping (
    ingredient_id INTEGER REFERENCES ingredients(id) ON DELETE CASCADE,
    category_id INTEGER REFERENCES ingredient_categories(id) ON DELETE CASCADE,
    
    PRIMARY KEY (ingredient_id, category_id)
);