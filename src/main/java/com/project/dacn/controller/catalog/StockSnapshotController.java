package com.project.dacn.controller.catalog;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.catalog.StockSnapshotRequest;
import com.project.dacn.dto.response.catalog.StockSnapshotResponse;
import com.project.dacn.service.catalog.StockSnapshotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
public class StockSnapshotController {

    private final StockSnapshotService service;

    @PostMapping("/snapshots")
    public ResponseEntity<ApiResponse<StockSnapshotResponse>> snapshots(
            @Valid @RequestBody StockSnapshotRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.snapshots(request)));
    }
}
