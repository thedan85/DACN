package com.project.dacn.repository.identity;

import com.project.dacn.entity.identity.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByCustomerId(String customerId);

    /** Serializes absent-cart creation through the owning customer row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    Optional<Customer> findByUser_UserId(String userId);

    Optional<Customer> findByUser_Email(String email);

    /**
     * Writes only the columns the request supplied. A profile edit must not
     * overwrite a concurrent edit to a field the caller never touched.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Customer c
               set c.name = coalesce(:name, c.name),
                   c.phone = coalesce(:phone, c.phone),
                   c.address = case when :addressProvided = true then :address else c.address end
             where c.customerId = :customerId
            """)
    int updateProfileFields(@Param("customerId") String customerId,
                            @Param("name") String name,
                            @Param("phone") String phone,
                            @Param("addressProvided") boolean addressProvided,
                            @Param("address") String address);
}
