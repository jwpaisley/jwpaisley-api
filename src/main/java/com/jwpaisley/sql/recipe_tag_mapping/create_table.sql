CREATE TABLE recipe_tag_mapping (
    recipe_id UUID REFERENCES recipes(id) ON DELETE CASCADE,
    tag_id UUID REFERENCES recipe_tags(id) ON DELETE CASCADE,

    PRIMARY KEY (recipe_id, tag_id)
);