package com.ctms.repository;

import com.ctms.model.Container;
import com.ctms.model.YardSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface YardSlotRepository extends JpaRepository<YardSlot, Long> {
    List<YardSlot> findByBlockOrderBySlotIndexAsc(YardSlot.YardBlock block);
    Optional<YardSlot> findBySlotCode(String slotCode);
    Optional<YardSlot> findByContainer(Container container);
    List<YardSlot> findByContainerIsNull();
    long countByContainerIsNotNull();
}
