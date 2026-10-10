package com.project.dacn.integration.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudinaryConfig {

    private final CloudinaryProperties properties;

    public CloudinaryConfig(CloudinaryProperties properties) {
        this.properties = properties;
    }

    @Bean
    public Cloudinary cloudinary() {
        if (isBlank(properties.getCloudName())
                || isBlank(properties.getApiKey())
                || isBlank(properties.getApiSecret())) {
            throw new IllegalStateException(
                    "Missing Cloudinary config. Set CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET env vars.");
        }
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", properties.getCloudName(),
                "api_key", properties.getApiKey(),
                "api_secret", properties.getApiSecret(),
                "secure", properties.isSecure()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
