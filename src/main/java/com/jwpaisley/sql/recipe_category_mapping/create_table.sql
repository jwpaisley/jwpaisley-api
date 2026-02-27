CREATE TABLE recipe_category_mapping (
    recipe_id UUID REFERENCES recipes(id) ON DELETE CASCADE,
    category_id UUID REFERENCES recipe_categories(id) ON DELETE CASCADE,
    
    PRIMARY KEY (recipe_id, category_id)
);