package com.project.dacn;

import com.project.dacn.entity.Role;
import com.project.dacn.entity.User;
import com.project.dacn.common.constant.UserStatus;
import com.project.dacn.dto.request.CustomerCreateRequest;
import com.project.dacn.dto.request.CustomerUpdateRequest;
import com.project.dacn.dto.response.CustomerResponse;
import com.project.dacn.entity.Customer;
import com.project.dacn.mapper.CustomerMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityMapperTest {

    private final CustomerMapper mapper = Mappers.getMapper(CustomerMapper.class);

    @Test
    void customerRoundTripFlattensUser() {
        Customer customer = mapper.toEntity(new CustomerCreateRequest("u-1", "C", null, "090"));
        assertThat(customer.getCustomerId()).isNull();
        assertThat(customer.getUser()).isNull();

        Role role = Role.builder().code("c").name("C").build();
        User user = User.builder().email("c@x.com").passwordHash("h")
                .status(UserStatus.ACTIVE).role(role).build();
        user.setUserId("u-1");
        customer.setUser(user);
        customer.setCustomerId("cust-1");

        CustomerResponse response = mapper.toResponse(customer);
        assertThat(response.userId()).isEqualTo("u-1");
        assertThat(response.userEmail()).isEqualTo("c@x.com");

        mapper.updateEntity(new CustomerUpdateRequest(null, "New addr", null), customer);
        assertThat(customer.getName()).isEqualTo("C");
        assertThat(customer.getAddress()).isEqualTo("New addr");
    }
}
