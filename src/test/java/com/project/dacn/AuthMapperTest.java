package com.project.dacn;

import com.project.dacn.dto.request.RoleCreateRequest;
import com.project.dacn.dto.request.RoleUpdateRequest;
import com.project.dacn.dto.request.UserCreateRequest;
import com.project.dacn.dto.request.UserUpdateRequest;
import com.project.dacn.dto.response.RoleResponse;
import com.project.dacn.dto.response.UserResponse;
import com.project.dacn.entity.Role;
import com.project.dacn.entity.User;
import com.project.dacn.mapper.RoleMapper;
import com.project.dacn.mapper.UserMapper;
import com.project.dacn.common.constant.UserStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class AuthMapperTest {

    private final RoleMapper roleMapper = Mappers.getMapper(RoleMapper.class);
    private final UserMapper userMapper = Mappers.getMapper(UserMapper.class);

    @Test
    void roleCreateIgnoresBusinessIdAndMapsScalars() {
        Role role = roleMapper.toEntity(new RoleCreateRequest("admin", "Admin", "desc", true));
        assertThat(role.getRoleId()).isNull();
        assertThat(role.getCode()).isEqualTo("admin");
        assertThat(role.getSystem()).isTrue();

        role.setRoleId("fixed-id");
        RoleResponse response = roleMapper.toResponse(role);
        assertThat(response.roleId()).isEqualTo("fixed-id");
        assertThat(response.code()).isEqualTo("admin");
    }

    @Test
    void roleUpdateSkipsNulls() {
        Role role = Role.builder().code("c").name("Old").description("d").system(false).build();
        roleMapper.updateEntity(new RoleUpdateRequest("New", null, null, null), role);
        assertThat(role.getName()).isEqualTo("New");
        assertThat(role.getDescription()).isEqualTo("d");
        assertThat(role.getSystem()).isFalse();
    }

    @Test
    void userResponseFlattensRoleAndHidesPasswordHash() {
        Role role = Role.builder().code("customer").name("Customer").build();
        role.setRoleId("role-1");
        User user = User.builder().email("a@x.com").passwordHash("secret")
                .status(UserStatus.ACTIVE).role(role).build();
        user.setUserId("user-1");

        UserResponse response = userMapper.toResponse(user);
        assertThat(response.userId()).isEqualTo("user-1");
        assertThat(response.roleId()).isEqualTo("role-1");
        assertThat(response.roleName()).isEqualTo("Customer");
        assertThat(response.getClass().getRecordComponents())
                .noneMatch(c -> c.getName().equalsIgnoreCase("passwordHash"));
    }

    @Test
    void userCreateIgnoresBusinessIdAndRole() {
        User user = userMapper.toEntity(new UserCreateRequest("b@x.com", "hash", "role-9", null));
        assertThat(user.getUserId()).isNull();
        assertThat(user.getRole()).isNull();
        assertThat(user.getEmail()).isEqualTo("b@x.com");
    }

    @Test
    void userUpdateSkipsNullsAndNeverTouchesPasswordOrRole() {
        Role role = Role.builder().code("c").name("C").build();
        User user = User.builder().email("old@x.com").passwordHash("keep")
                .status(UserStatus.ACTIVE).role(role).build();
        userMapper.updateEntity(new UserUpdateRequest(null, UserStatus.LOCKED), user);
        assertThat(user.getEmail()).isEqualTo("old@x.com");
        assertThat(user.getPasswordHash()).isEqualTo("keep");
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.getRole()).isSameAs(role);
    }
}
