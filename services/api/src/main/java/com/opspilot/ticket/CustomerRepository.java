package com.opspilot.ticket;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

  Optional<Customer> findByIdAndOrganizationId(UUID id, UUID organizationId);

  boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

  List<Customer> findAllByOrganizationId(UUID organizationId);
}
