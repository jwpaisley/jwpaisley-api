package com.jwpaisley.services;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.javalin.http.UploadedFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.ImageInputStream;

public class StorageService {
    private static final long MAX_UPLOAD_SIZE_BYTES = 100L * 1024L * 1024L;
    private final Storage storage;

    private StorageService() {
        this.storage = StorageOptions.getDefaultInstance().getService();
    }

    private static class Holder {
        private static final StorageService INSTANCE = new StorageService();
    }

    public static StorageService getInstance() {
        return Holder.INSTANCE;
    }

    public String uploadFile(UploadedFile file, String bucketName) throws IOException {
        return uploadFileWithObjectKey(file, bucketName, UUID.randomUUID().toString()).get("url");
    }

    public Map<String, String> uploadFileWithThumbnail(UploadedFile file, String bucketName) throws IOException {
        return uploadFileWithObjectKey(file, bucketName, UUID.randomUUID().toString());
    }

    public Map<String, String> uploadFileWithObjectKey(UploadedFile file, String bucketName, String objectKey) throws IOException {
        String thumbnailObjectKey = "thumb-" + objectKey;
        byte[] fileBytes = file.content().readAllBytes();
        if (fileBytes.length > MAX_UPLOAD_SIZE_BYTES) {
            throw new IOException("Image is too large to process");
        }
        byte[] thumbnailBytes = createThumbnail(fileBytes, file.contentType());

        Map<String, String> response = new HashMap<>();
        response.put("url", uploadBytes(bucketName, objectKey, file.contentType(), fileBytes));
        response.put("thumbnailUrl", uploadBytes(bucketName, thumbnailObjectKey, file.contentType(), thumbnailBytes));
        return response;
    }

    public boolean deleteFile(String bucketName, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }

        BlobId blobId = BlobId.of(bucketName, objectKey);
        return storage.delete(blobId);
    }

    private String uploadBytes(String bucketName, String objectKey, String contentType, byte[] fileBytes) {
        BlobId blobId = BlobId.of(bucketName, objectKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType(contentType)
            .build();

        storage.create(blobInfo, fileBytes);
        return String.format("https://storage.googleapis.com/%s/%s", bucketName, objectKey);
    }

    private byte[] createThumbnail(byte[] fileBytes, String contentType) throws IOException {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IOException("Image content is empty");
        }

        try (ImageInputStream inputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(fileBytes))) {
            if (inputStream == null) {
                throw new IOException("Unable to create image input stream");
            }

            ImageReader reader = ImageIO.getImageReaders(inputStream).next();
            if (reader == null) {
                throw new IOException("Unable to decode image for thumbnail generation");
            }

            try {
                reader.setInput(inputStream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int targetSize = 320;
                double scale = Math.min(1.0, targetSize / (double) Math.max(width, height));
                int scaledWidth = Math.max(1, (int) Math.round(width * scale));
                int scaledHeight = Math.max(1, (int) Math.round(height * scale));

                ImageReadParam param = reader.getDefaultReadParam();
                int subsampling = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) targetSize));
                if (subsampling > 1) {
                    param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                }

                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new IOException("Unable to decode image for thumbnail generation");
                }

                int cropX = Math.max(0, (image.getWidth() - scaledWidth) / 2);
                int cropY = Math.max(0, (image.getHeight() - scaledHeight) / 2);
                BufferedImage thumbnail = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = thumbnail.createGraphics();
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, targetSize, targetSize, cropX, cropY, cropX + scaledWidth, cropY + scaledHeight, null);
                graphics.dispose();

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                String formatName = "jpg";
                if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("png")) {
                    formatName = "png";
                } else if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("gif")) {
                    formatName = "gif";
                }

                boolean written = ImageIO.write(image, formatName, outputStream);
                if (!written) {
                    written = ImageIO.write(image, "jpg", outputStream);
                }

                if (!written || outputStream.size() == 0) {
                    throw new IOException("Thumbnail generation produced an empty image stream");
                }

                return outputStream.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }
}