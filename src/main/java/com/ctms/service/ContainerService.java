package com.ctms.service;

import com.ctms.model.Container;
import com.ctms.model.MovementHistory;
import com.ctms.model.ShippingLine;
import com.ctms.model.YardSlot;
import com.ctms.repository.ContainerRepository;
import com.ctms.repository.MovementHistoryRepository;
import com.ctms.repository.ShippingLineRepository;
import com.ctms.repository.YardSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ContainerService {

    private final ContainerRepository containerRepository;
    private final MovementHistoryRepository movementHistoryRepository;
    private final ShippingLineRepository shippingLineRepository;
    private final YardSlotRepository yardSlotRepository;

    public List<Container> search(String query,
                                  Container.ContainerType type,
                                  Container.CargoStatus cargoStatus,
                                  Container.Condition condition,
                                  Container.TrackingStatus currentStatus,
                                  Long shippingLineId) {
        if (query != null && query.trim().isEmpty()) {
            query = null;
        }
        return containerRepository.searchContainers(query, type, cargoStatus, condition, currentStatus, shippingLineId);
    }

    public List<Container> findAll() {
        return containerRepository.findAll();
    }

    public List<Container> findByShippingLine(Long shippingLineId) {
        return containerRepository.findByShippingLineId(shippingLineId);
    }

    public Optional<Container> findById(Long id) {
        return containerRepository.findById(id);
    }

    public Optional<Container> findByContainerNumber(String number) {
        return containerRepository.findByContainerNumber(number);
    }

    public List<MovementHistory> getMovementHistory(Long containerId) {
        return movementHistoryRepository.findByContainerIdOrderByTimestampDesc(containerId);
    }

    @Transactional
    public Container save(Container container) {
        return containerRepository.save(container);
    }

    @Transactional
    public Container registerManifestContainer(Long shippingLineId,
                                               String containerNumber,
                                               String isoType,
                                               Container.ContainerType containerType,
                                               Container.CargoStatus cargoStatus,
                                               String sealNumber,
                                               Double grossWeightKg,
                                               String customsClearanceCode) {
        if (customsClearanceCode == null || customsClearanceCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Bắt buộc phải nhập Mã tờ khai / Giấy thông quan Hải quan cho Container!");
        }
        String cleanContNo = containerNumber.trim().toUpperCase();
        if (containerRepository.findByContainerNumber(cleanContNo).isPresent()) {
            throw new IllegalArgumentException("Container " + cleanContNo + " đã tồn tại trong hệ thống!");
        }
        ShippingLine line = shippingLineRepository.findById(shippingLineId)
                .orElseGet(() -> shippingLineRepository.findAll().stream().findFirst().orElse(null));

        Container.ContainerType effectiveType = containerType != null ? containerType : Container.ContainerType.DRY;
        YardSlot.YardBlock targetBlock = effectiveType.getPreferredBlock();
        List<YardSlot> zoneEmptySlots = yardSlotRepository.findByBlockAndContainerIsNullOrderBySlotIndexAsc(targetBlock);
        List<YardSlot> fallbackEmptySlots = yardSlotRepository.findByContainerIsNull();
        YardSlot assignedSlot = !zoneEmptySlots.isEmpty() ? zoneEmptySlots.get(0)
                : (fallbackEmptySlots.isEmpty() ? null : fallbackEmptySlots.get(0));

        Container container = Container.builder()
                .containerNumber(cleanContNo)
                .isoType(isoType != null && !isoType.isBlank() ? isoType.trim().toUpperCase() : "40HC")
                .containerType(effectiveType)
                .cargoStatus(cargoStatus != null ? cargoStatus : Container.CargoStatus.FULL)
                .condition(Container.Condition.GOOD)
                .currentStatus(assignedSlot != null ? Container.TrackingStatus.IN_YARD : Container.TrackingStatus.EXPECTED)
                .sealNumber(sealNumber != null && !sealNumber.isBlank() ? sealNumber.trim().toUpperCase() : "SL-MANIFEST")
                .grossWeightKg(grossWeightKg != null ? grossWeightKg : 24500.0)
                .shippingLine(line)
                .gateInTime(LocalDateTime.now())
                .customsClearanceCode(customsClearanceCode.trim().toUpperCase())
                .customsCleared(true)
                .build();
        container = containerRepository.save(container);

        if (assignedSlot != null) {
            assignedSlot.setContainer(container);
            container.setCurrentSlot(assignedSlot);
            yardSlotRepository.save(assignedSlot);

            movementHistoryRepository.save(MovementHistory.builder()
                    .container(container)
                    .eventType(MovementHistory.MovementType.ASSIGN_SLOT)
                    .fromLocation("Khai báo Manifest Hãng tàu (" + (line != null ? line.getCode() : "LINE") + ")")
                    .toLocation("Ô bãi " + assignedSlot.getSlotCode() + " (" + assignedSlot.getBlock().getDisplayName() + ")")
                    .operatorName(line != null ? line.getName() : "Shipping Line")
                    .timestamp(LocalDateTime.now())
                    .notes("Dỡ hàng từ tàu / Khai báo Manifest kèm Giấy thông quan HQ: " + container.getCustomsClearanceCode())
                    .build());
        }
        return container;
    }
}
