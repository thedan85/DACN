package com.project.dacn.common.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceResponse<T> {
    private boolean success;
    private String message;
    private T data;

    public static <T> ServiceResponse<T> ok(T data) {
        return new ServiceResponse<>(true, "OK", data);
    }

    public static <T> ServiceResponse<T> fail(String message) {
        return new ServiceResponse<>(false, message, null);
    }
}
