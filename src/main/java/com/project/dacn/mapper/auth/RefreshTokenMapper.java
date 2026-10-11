package com.project.dacn.mapper.auth;

import com.project.dacn.dto.request.auth.RefreshTokenCreateRequest;
import com.project.dacn.dto.response.auth.RefreshTokenResponse;
import com.project.dacn.entity.auth.RefreshToken;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RefreshTokenMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tokenId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "revoked", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    RefreshToken toEntity(RefreshTokenCreateRequest request);

    @Mapping(source = "user.userId", target = "userId")
    RefreshTokenResponse toResponse(RefreshToken token);
}
