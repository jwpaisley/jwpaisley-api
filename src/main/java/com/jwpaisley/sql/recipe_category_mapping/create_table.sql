CREATE TABLE recipe_category_mapping (
    recipe_id INTEGER REFERENCES recipes(id) ON DELETE CASCADE,
    category_id INTEGER REFERENCES recipe_categories(id) ON DELETE CASCADE,
    
    PRIMARY KEY (recipe_id, category_id)
);