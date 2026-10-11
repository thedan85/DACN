package com.project.dacn.modules.identity.entity;

import com.project.dacn.modules.auth.entity.User;
import com.project.dacn.modules.common.entity.BaseEntity;
import com.project.dacn.modules.common.enums.EmployeeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.NaturalId;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "employees")
public class Employee extends BaseEntity {

    @NaturalId
    @Column(name = "employee_id", nullable = false, unique = true)
    private String employeeId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", unique = true,
            foreignKey = @ForeignKey(name = "fk_employees_user"))
    private User user;

    @Column(name = "employee_name", nullable = false)
    private String name;

    @Column(name = "employee_address")
    private String address;

    @Column(name = "employee_phone", nullable = false, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "employee_status", nullable = false, length = 20)
    @lombok.Builder.Default
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Override
    protected String getBusinessId() {
        return employeeId;
    }

    @Override
    protected void setBusinessId(String businessId) {
        this.employeeId = businessId;
    }
}
