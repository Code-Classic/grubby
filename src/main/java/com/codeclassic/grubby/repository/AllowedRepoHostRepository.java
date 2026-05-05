package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.AllowedRepoHost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AllowedRepoHostRepository extends JpaRepository<AllowedRepoHost, Long> {
    Optional<AllowedRepoHost> findByHostnameIgnoreCaseAndEnabledTrue(String hostname);
    List<AllowedRepoHost> findAllByEnabledTrue();
}
