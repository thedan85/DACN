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
        try {
            return (Map<String, Object>) cloudinary.uploader().upload(
                    bytes,
                    ObjectUtils.asMap(
                            "folder", folder,
                            "resource_type", "auto"));
        } catch (IOException e) {
            throw new CloudinaryException("Cloudinary upload failed", e);
        }
    }

    public String uploadAndGetUrl(byte[] bytes, String folder) {
        Map<String, Object> result = upload(bytes, folder);
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
