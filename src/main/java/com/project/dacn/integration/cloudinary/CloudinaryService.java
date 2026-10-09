package com.project.dacn.integration.cloudinary;

import org.springframework.stereotype.Service;

@Service
public class CloudinaryService {

    private final CloudinaryProperties properties;

    public CloudinaryService(CloudinaryProperties properties) {
        this.properties = properties;
    }

    public String upload(byte[] bytes, String folder) {
        // TODO: implement upload via Cloudinary client
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
