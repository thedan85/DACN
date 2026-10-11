package com.project.dacn.mapper.auth;

import com.project.dacn.dto.request.auth.RoleGrantRequest;
import com.project.dacn.dto.response.auth.RolePermissionResponse;
import com.project.dacn.entity.auth.RolePermission;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RolePermissionMapper {

    @Mapping(target = "role", ignore = true)
    @Mapping(target = "permission", ignore = true)
    RolePermission toEntity(RoleGrantRequest request);

    @Mapping(source = "role.roleId", target = "roleId")
    @Mapping(source = "role.name", target = "roleName")
    @Mapping(source = "permission.permissionId", target = "permissionId")
    @Mapping(source = "permission.code", target = "permissionCode")
    RolePermissionResponse toResponse(RolePermission grant);
}
