package com.jwpaisley.controllers;

import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.services.StorageService;
import com.jwpaisley.models.Book;
import com.jwpaisley.helpers.AuthHelper;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UploadedFile;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.io.IOException;

public class BooksController {

    public static Book bookFromResultSet(ResultSet rs) throws SQLException {
        return new Book(
            rs.getObject("id", java.util.UUID.class),
            rs.getString("title"),
            rs.getString("author"),
            rs.getString("cover_image"),
            rs.getString("description"),
            rs.getString("state"),

            rs.getInt("page_count"),
            rs.getInt("current_page"),
            rs.getInt("rating"),
            rs.getString("review"),
            rs.getTimestamp("start_date") != null ? rs.getTimestamp("start_date").toString() : null,
            rs.getTimestamp("finish_date") != null ? rs.getTimestamp("finish_date").toString() : null,

            rs.getTimestamp("created_at").toString(),
            rs.getTimestamp("updated_at").toString()
        );
    }
    
    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing the books archive");
    }


    public void getAll(Context ctx) {
        List<Book> books = new ArrayList<>();
        String sql = "SELECT * FROM books ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                books.add(bookFromResultSet(rs));
            }
            ctx.json(books);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
    
    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM books WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                ctx.json(bookFromResultSet(rs));
            } else {
                throw new NotFoundResponse("Book not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        Book newBook = ctx.bodyAsClass(Book.class);

        String sql = """
            INSERT INTO books (
                title, author, description, cover_image, state, page_count, current_page, rating, review,
                start_date, finish_date
            ) VALUES (?, ?, ?, ?, ?::book_read_state, ?, ?, ?, ?, ?, ?)
            RETURNING *;
        """;

        try (Connection conn = ds.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newBook.title());
            pstmt.setString(2, newBook.author());
            pstmt.setString(3, newBook.description());
            pstmt.setString(4, newBook.coverImage());
            pstmt.setString(5, newBook.state());
            pstmt.setInt(6, newBook.pageCount());
            pstmt.setInt(7, newBook.currentPage());
            pstmt.setInt(8, newBook.rating());
            pstmt.setString(9, newBook.review());
            pstmt.setTimestamp(10, newBook.startDate() != null ? Timestamp.valueOf(newBook.startDate()) : null);
            pstmt.setTimestamp(11, newBook.finishDate() != null ? Timestamp.valueOf(newBook.finishDate()) : null);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    UUID generatedId = rs.getObject("id", UUID.class);
                    String createdAt = rs.getTimestamp("created_at").toString();
                    String updatedAt = rs.getTimestamp("updated_at").toString();

                    Book savedBook = new Book(
                        generatedId,
                        newBook.title(),
                        newBook.author(),
                        newBook.coverImage(),
                        newBook.description(),
                        newBook.state(),
                        newBook.pageCount(),
                        newBook.currentPage(),
                        newBook.rating(),
                        newBook.review(),
                        newBook.startDate(),
                        newBook.finishDate(),
                        createdAt,
                        updatedAt
                    );

                    ctx.status(201).json(savedBook);
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error saving book to the archive");
        }
    }

    public void update(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        Book updatedBook = ctx.bodyAsClass(Book.class);

        String sql = """
            UPDATE books SET 
                title = ?, author = ?, description = ?, cover_image = ?, state = ?::book_read_state,
                page_count = ?, current_page = ?,
                rating = ?, review = ?, start_date = ?, finish_date = ?
            WHERE id = ?
        """;

        try (Connection conn = ds.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedBook.title());
            pstmt.setString(2, updatedBook.author());
            pstmt.setString(3, updatedBook.description());
            pstmt.setString(4, updatedBook.coverImage());
            pstmt.setString(5, updatedBook.state());
            pstmt.setInt(6, updatedBook.pageCount());
            pstmt.setInt(7, updatedBook.currentPage());
            pstmt.setInt(8, updatedBook.rating());
            pstmt.setString(9, updatedBook.review());
            pstmt.setTimestamp(10, updatedBook.startDate() != null ? Timestamp.valueOf(updatedBook.startDate()) : null);
            pstmt.setTimestamp(11, updatedBook.finishDate() != null ? Timestamp.valueOf(updatedBook.finishDate()) : null);
            pstmt.setObject(12, updatedBook.id());

            int rowsUpdated = pstmt.executeUpdate();

            if (rowsUpdated > 0) {
                ctx.status(200).json(updatedBook);
            } else {
                throw new NotFoundResponse("Book not found");
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error updating book in the archive");
        }
    }

    public void delete(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "DELETE FROM books WHERE id = ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Book not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void uploadCover(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }
        
        StorageService storageService = StorageService.getInstance();

        try {
            UploadedFile file = ctx.uploadedFile("cover");

            if (file != null) {
                String fileUrl = storageService.uploadFile(file, "jwpaisley-book-covers");
                
                ctx.json(Map.of("url", fileUrl));
            } else {
                ctx.status(400).result("No file uploaded");
            }
        } catch (IOException e) {
            System.err.println("Storage error: " + e.getMessage());
            ctx.status(500).result("Error uploading cover image");
        }
    }
}