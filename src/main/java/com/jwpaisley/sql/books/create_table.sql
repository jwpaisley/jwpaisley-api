CREATE TYPE book_read_state AS ENUM (
    'wantToRead', 
    'currentlyReading', 
    'finishedReading'
);

CREATE TABLE books (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL,
    author TEXT NOT NULL,
    cover_image TEXT,
    description TEXT,
    state book_read_state NOT NULL DEFAULT 'wantToRead',

    page_count INTEGER DEFAULT 1,
    current_page INTEGER DEFAULT 0,
    rating INTEGER DEFAULT 0,
    review TEXT DEFAULT '',
    start_date DATE,
    finish_date DATE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_books_modtime
BEFORE UPDATE ON books
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();