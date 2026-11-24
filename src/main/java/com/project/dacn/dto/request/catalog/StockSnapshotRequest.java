package com.project.dacn.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StockSnapshotRequest(
        @NotNull List<@NotBlank @Size(max = 255) String> productIds,
        @NotNull List<@NotBlank @Size(max = 255) String> variantIds) {
}
