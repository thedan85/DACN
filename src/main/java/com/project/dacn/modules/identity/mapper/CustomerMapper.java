package com.project.dacn.modules.identity.mapper;

import com.project.dacn.modules.identity.dto.request.CustomerCreateRequest;
import com.project.dacn.modules.identity.dto.request.CustomerUpdateRequest;
import com.project.dacn.modules.identity.dto.response.CustomerResponse;
import com.project.dacn.modules.identity.entity.Customer;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface CustomerMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "customerId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Customer toEntity(CustomerCreateRequest request);

    @Mapping(source = "user.userId", target = "userId")
    @Mapping(source = "user.email", target = "userEmail")
    CustomerResponse toResponse(Customer customer);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "customerId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(CustomerUpdateRequest request, @MappingTarget Customer customer);
}
