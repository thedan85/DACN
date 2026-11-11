package com.project.dacn.integration.shipping.ghn.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GhnWard(
        Integer id,
        String name,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) Integer status) {
}
