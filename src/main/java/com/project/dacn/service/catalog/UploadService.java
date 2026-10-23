package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.catalog.MediaCreateRequest;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.dto.response.catalog.UploadResponse;
import com.project.dacn.entity.catalog.Media;
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
// NOTE: @Transactional spans the Cloudinary network call so the lazy
// category/parent traversal in folder resolution stays in-session.
// Deliberate uni-scale tradeoff; split read/upload/write phases if upload
// traffic ever contends for DB connections.
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
        Media media = mediaService.createEntity(
                new MediaCreateRequest(url, product.getProductId(), null));
        product.setThumbnail(media);
        productRepo.save(product);
        return new UploadResponse(url, folder, product.getProductId(), null, media.getMediaId());
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
        byte[] bytes;
        try {
            bytes = file == null ? new byte[0] : file.getBytes();
        } catch (IOException e) {
            throw new CloudinaryException("failed to read upload", e);
        }
        validateImage(file, bytes);
        return bytes;
    }

    private void validateImage(MultipartFile file, byte[] bytes) {
        if (file == null || bytes.length == 0) {
            throw new IllegalArgumentException("file must not be empty");
        }
        if (bytes.length > MAX_SIZE) {
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
        // Content-Type and extension are client-controlled, so require a
        // matching magic-byte signature before handing bytes to Cloudinary.
        String detected = detectImageType(bytes);
        if (detected == null || !detected.equals(contentType)) {
            throw new IllegalArgumentException("file content does not match declared image type");
        }
    }

    private static String detectImageType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N'
                && b[3] == 'G' && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        if (b.length >= 6 && (startsWith(b, 0, "GIF87a") || startsWith(b, 0, "GIF89a"))) {
            return "image/gif";
        }
        if (b.length >= 12 && startsWith(b, 0, "RIFF") && startsWith(b, 8, "WEBP")) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int offset, String ascii) {
        if (bytes.length < offset + ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (bytes[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
