package com.ctms.service;

import com.ctms.model.Container;
import com.ctms.model.MovementHistory;
import com.ctms.model.YardSlot;
import com.ctms.repository.ContainerRepository;
import com.ctms.repository.MovementHistoryRepository;
import com.ctms.repository.YardSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class YardService {

    private final YardSlotRepository yardSlotRepository;
    private final ContainerRepository containerRepository;
    private final MovementHistoryRepository movementHistoryRepository;

    public YardService(YardSlotRepository yardSlotRepository,
                       ContainerRepository containerRepository,
                       MovementHistoryRepository movementHistoryRepository) {
        this.yardSlotRepository = yardSlotRepository;
        this.containerRepository = containerRepository;
        this.movementHistoryRepository = movementHistoryRepository;
    }

    public List<YardSlot> getSlotsByBlock(YardSlot.YardBlock block) {
        return yardSlotRepository.findByBlockOrderBySlotIndexAsc(block);
    }

    public List<YardSlot> getAllSlots() {
        return yardSlotRepository.findAll();
    }

    public List<YardSlot> getEmptySlots() {
        return yardSlotRepository.findByContainerIsNull();
    }

    public Optional<YardSlot> findById(Long id) {
        return yardSlotRepository.findById(id);
    }

    public long getOccupiedCount() {
        return yardSlotRepository.countByContainerIsNotNull();
    }

    public long getTotalCount() {
        return yardSlotRepository.count();
    }

    @Transactional
    public void assignContainerToSlot(Long containerId, Long slotId, String operatorName) {
        Container container = containerRepository.findById(containerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy container ID: " + containerId));

        YardSlot targetSlot = yardSlotRepository.findById(slotId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ô bãi ID: " + slotId));

        if (targetSlot.isOccupied()) {
            throw new IllegalStateException("Ô bãi " + targetSlot.getSlotCode() + " đã có container!");
        }
        validateZoneSafety(container, targetSlot);

        // Kiểm tra xem container có đang ở ô khác không
        Optional<YardSlot> currentSlotOpt = yardSlotRepository.findByContainer(container);
        String fromLoc = "Khu vực tiếp nhận cổng / Chờ xếp bãi";
        if (currentSlotOpt.isPresent()) {
            YardSlot oldSlot = currentSlotOpt.get();
            oldSlot.setContainer(null);
            yardSlotRepository.save(oldSlot);
            fromLoc = "Ô bãi " + oldSlot.getSlotCode();
        }

        targetSlot.setContainer(container);
        yardSlotRepository.save(targetSlot);

        container.setCurrentStatus(Container.TrackingStatus.IN_YARD);
        containerRepository.save(container);

        // Lưu lịch sử di chuyển (Record Container Movement)
        MovementHistory history = MovementHistory.builder()
                .container(container)
                .eventType(MovementHistory.MovementType.ASSIGN_SLOT)
                .fromLocation(fromLoc)
                .toLocation("Ô bãi " + targetSlot.getSlotCode() + " (" + targetSlot.getBlock().getDisplayName() + ")")
                .operatorName(operatorName != null ? operatorName : "Terminal Operator")
                .timestamp(LocalDateTime.now())
                .notes("Hạ bãi container vào vị trí chỉ định")
                .build();
        movementHistoryRepository.save(history);
    }

    @Transactional
    public void moveContainerBetweenSlots(Long fromSlotId, Long toSlotId, String operatorName) {
        YardSlot fromSlot = yardSlotRepository.findById(fromSlotId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ô bãi nguồn!"));
        YardSlot toSlot = yardSlotRepository.findById(toSlotId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ô bãi đích!"));

        if (!fromSlot.isOccupied()) {
            throw new IllegalStateException("Ô nguồn " + fromSlot.getSlotCode() + " hiện không có container!");
        }
        if (toSlot.isOccupied()) {
            throw new IllegalStateException("Ô đích " + toSlot.getSlotCode() + " đã có container!");
        }

        Container container = fromSlot.getContainer();
        validateZoneSafety(container, toSlot);
        fromSlot.setContainer(null);
        toSlot.setContainer(container);

        yardSlotRepository.save(fromSlot);
        yardSlotRepository.save(toSlot);

        // Ghi nhật ký di chuyển (<<include>> Record Container Movement)
        MovementHistory history = MovementHistory.builder()
                .container(container)
                .eventType(MovementHistory.MovementType.MOVE_SLOT)
                .fromLocation("Ô bãi " + fromSlot.getSlotCode())
                .toLocation("Ô bãi " + toSlot.getSlotCode())
                .operatorName(operatorName != null ? operatorName : "Terminal Operator")
                .timestamp(LocalDateTime.now())
                .notes("Đảo chuyển container giữa các vị trí bãi")
                .build();
        movementHistoryRepository.save(history);
    }

    /**
     * Quy tắc An toàn Quy hoạch Bãi Cảng (IMDG & Smart Zoning):
     * - Hàng Cháy Nổ (HAZMAT) bắt buộc cách ly tại Khu C (BLOCK_C), nghiêm cấm xếp vào Khu A/B.
     * - Hàng Đông Lạnh (REEFER) không được xếp chung vào Khu C (Khu cách ly cháy nổ).
     */
    private void validateZoneSafety(Container container, YardSlot targetSlot) {
        if (container == null || container.getContainerType() == null || targetSlot == null) {
            return;
        }
        if (container.getContainerType() == Container.ContainerType.HAZMAT
                && targetSlot.getBlock() != YardSlot.YardBlock.BLOCK_C) {
            throw new IllegalStateException("CẢNH BÁO AN TOÀN PCCC (IMDG): Container Hàng Cháy Nổ (" + container.getContainerNumber()
                    + ") bắt buộc phải cách ly tại Khu C (Block C), nghiêm cấm xếp vào " + targetSlot.getBlock().getDisplayName() + "!");
        }
        if (container.getContainerType() == Container.ContainerType.REEFER
                && targetSlot.getBlock() == YardSlot.YardBlock.BLOCK_C) {
            throw new IllegalStateException("CẢNH BÁO AN TOÀN ĐIỆN LẠNH: Container Đông Lạnh (" + container.getContainerNumber()
                    + ") cần nguồn điện 3 pha tại Khu B, không được xếp vào Khu C (Khu cách ly cháy nổ)!");
        }
    }
}
