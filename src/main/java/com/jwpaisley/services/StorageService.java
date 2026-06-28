package com.jwpaisley.services;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.javalin.http.UploadedFile;
import java.io.IOException;
import java.util.UUID;

public class StorageService {
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
        String fileName = UUID.randomUUID() + "-" + file.filename();
        BlobId blobId = BlobId.of(bucketName, fileName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType(file.contentType())
            .build();

        storage.create(blobInfo, file.content().readAllBytes());
        
        return String.format("https://storage.googleapis.com/%s/%s", bucketName, fileName);
    }
}