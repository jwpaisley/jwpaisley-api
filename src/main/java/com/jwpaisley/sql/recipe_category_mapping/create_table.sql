CREATE TABLE recipe_category_mapping (
    recipe_id SERIAL REFERENCES recipes(id) ON DELETE CASCADE,
    category_id SERIAL REFERENCES recipe_categories(id) ON DELETE CASCADE,
    
    PRIMARY KEY (recipe_id, category_id)
);