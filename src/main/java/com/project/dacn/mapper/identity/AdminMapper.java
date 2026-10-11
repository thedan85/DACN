package com.project.dacn.mapper.identity;

import com.project.dacn.dto.request.identity.AdminCreateRequest;
import com.project.dacn.dto.request.identity.AdminUpdateRequest;
import com.project.dacn.dto.response.identity.AdminResponse;
import com.project.dacn.entity.identity.Admin;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface AdminMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "adminId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Admin toEntity(AdminCreateRequest request);

    @Mapping(source = "user.userId", target = "userId")
    AdminResponse toResponse(Admin admin);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "adminId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(AdminUpdateRequest request, @MappingTarget Admin admin);
}
