package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.catalog.MediaCreateRequest;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.dto.response.catalog.UploadResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.integration.cloudinary.CloudinaryException;
import com.project.dacn.integration.cloudinary.CloudinaryService;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

@Service
@Transactional
@RequiredArgsConstructor
public class UploadService {

    static final long MAX_SIZE = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif");

    private final ProductRepository productRepo;
    private final VariantRepository variantRepo;
    private final MediaService mediaService;
    private final ProductFolderResolver folderResolver;
    private final CloudinaryService cloudinaryService;

    public UploadResponse uploadProductThumbnail(String productId, MultipartFile file) {
        Product product = productRepo.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("product not found: " + productId));
        byte[] bytes = readAndValidate(file);
        String folder = folderResolver.resolve(product);
        String publicId = product.getProductId() + "-" + System.currentTimeMillis();
        // Upload first: a Cloudinary failure leaves the DB untouched (old thumbnail kept).
        String url = cloudinaryService.uploadAndGetUrl(bytes, folder, publicId);
        product.setThumbnail(url);
        productRepo.save(product);
        return new UploadResponse(url, folder, product.getProductId(), null, null);
    }

    public UploadResponse uploadVariantImage(String variantId, MultipartFile file) {
        Variant variant = variantRepo.findByVariantId(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("variant not found: " + variantId));
        Product product = variant.getProduct();
        if (product == null) {
            throw new ResourceNotFoundException("product not found for variant: " + variantId);
        }
        byte[] bytes = readAndValidate(file);
        String folder = folderResolver.resolve(product);
        String skuPart = ProductFolderResolver.slugify(variant.getSku());
        String publicId = product.getProductId() + "-" + skuPart + "-" + System.currentTimeMillis();
        // Upload first: a Cloudinary failure creates no Media row.
        String url = cloudinaryService.uploadAndGetUrl(bytes, folder, publicId);
        MediaResponse media = mediaService.create(new MediaCreateRequest(url, null, variant.getVariantId()));
        return new UploadResponse(url, folder, product.getProductId(), variant.getVariantId(), media.mediaId());
    }

    private byte[] readAndValidate(MultipartFile file) {
        validateImage(file);
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new CloudinaryException("failed to read upload", e);
        }
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("file too large: max 10MB");
        }
        String contentType = file.getContentType() != null
                ? file.getContentType().toLowerCase(Locale.ROOT)
                : "";
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported image type: " + file.getContentType());
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("unsupported image extension: " + filename);
        }
    }
}
