package com.project.dacn.integration.shipping.ghn.dto;

public record GhnMasterDataResponse<T>(Integer code, String message, T data) {
}
