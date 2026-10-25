package com.project.dacn.mapper.auth;

import com.project.dacn.dto.request.auth.RoleCreateRequest;
import com.project.dacn.dto.request.auth.RoleUpdateRequest;
import com.project.dacn.dto.response.auth.RoleResponse;
import com.project.dacn.entity.auth.Role;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface RoleMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roleId", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Role toEntity(RoleCreateRequest request);

    RoleResponse toResponse(Role role);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roleId", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(RoleUpdateRequest request, @MappingTarget Role role);
}
