package com.project.dacn.integration.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class CloudinaryService {

    private final Cloudinary cloudinary;

    public CloudinaryService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> upload(byte[] bytes, String folder) {
        return upload(bytes, folder, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> upload(byte[] bytes, String folder, String publicId) {
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>(ObjectUtils.asMap(
                    "folder", folder,
                    "resource_type", "auto",
                    "overwrite", false,
                    "unique_filename", false));
            if (publicId != null && !publicId.isBlank()) {
                params.put("public_id", publicId);
            }
            return (Map<String, Object>) cloudinary.uploader().upload(bytes, params);
        } catch (IOException e) {
            throw new CloudinaryException("Cloudinary upload failed", e);
        }
    }

    public String uploadAndGetUrl(byte[] bytes, String folder) {
        return uploadAndGetUrl(bytes, folder, null);
    }

    public String uploadAndGetUrl(byte[] bytes, String folder, String publicId) {
        Map<String, Object> result = upload(bytes, folder, publicId);
        Object url = result.get("secure_url");
        return url != null ? url.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> delete(String publicId) {
        try {
            return (Map<String, Object>) cloudinary.uploader().destroy(
                    publicId,
                    ObjectUtils.asMap("resource_type", "image"));
        } catch (IOException e) {
            throw new CloudinaryException("Cloudinary delete failed: " + publicId, e);
        }
    }
}
