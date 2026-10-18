package com.ctms.repository;

import com.ctms.model.Berth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BerthRepository extends JpaRepository<Berth, Long> {
    Optional<Berth> findByBerthCode(String berthCode);
    List<Berth> findByStatus(Berth.BerthStatus status);
}
