package com.ctms.service;

import com.ctms.model.Appointment;
import com.ctms.model.Container;
import com.ctms.model.MovementHistory;
import com.ctms.model.YardSlot;
import com.ctms.repository.AppointmentRepository;
import com.ctms.repository.ContainerRepository;
import com.ctms.repository.MovementHistoryRepository;
import com.ctms.repository.YardSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class GateService {

    private final ContainerRepository containerRepository;
    private final AppointmentRepository appointmentRepository;
    private final MovementHistoryRepository movementHistoryRepository;
    private final YardSlotRepository yardSlotRepository;

    public GateService(ContainerRepository containerRepository,
                       AppointmentRepository appointmentRepository,
                       MovementHistoryRepository movementHistoryRepository,
                       YardSlotRepository yardSlotRepository) {
        this.containerRepository = containerRepository;
        this.appointmentRepository = appointmentRepository;
        this.movementHistoryRepository = movementHistoryRepository;
        this.yardSlotRepository = yardSlotRepository;
    }

    /**
     * Use Case phụ: <<include>> Verify Container (Seal, Condition, Booking)
     */
    public VerificationResult verifyContainer(String containerNumber, String sealNumber, String bookingNumber) {
        boolean valid = true;
        StringBuilder message = new StringBuilder();

        if (containerNumber == null || containerNumber.trim().isEmpty()) {
            return new VerificationResult(false, "Mã container không được để trống!");
        }

        if (sealNumber == null || sealNumber.trim().isEmpty()) {
            valid = false;
            message.append("Cảnh báo: Thiếu số chì (Seal) niêm phong. ");
        }

        if (bookingNumber != null && !bookingNumber.trim().isEmpty()) {
            Optional<Appointment> appOpt = appointmentRepository.findByBookingNumber(bookingNumber.trim());
            if (appOpt.isPresent()) {
                message.append("Khớp thông tin Booking: ").append(bookingNumber).append(". ");
            } else {
                message.append("Thông tin Booking nhập thủ công. ");
            }
        }

        return new VerificationResult(valid, message.toString());
    }

    /**
     * Use Case chính: Confirm Gate In
     * <<include>> Verify Container
     * <<include>> Record Container Movement
     */
    @Transactional
    public Container confirmGateIn(String containerNumber,
                                   String sealNumber,
                                   Container.ContainerType containerType,
                                   Container.CargoStatus cargoStatus,
                                   Container.Condition condition,
                                   String bookingNumber,
                                   String licensePlate,
                                   String customsClearanceCode,
                                   String operatorName) {

        // 1. Kiểm tra xác minh (<<include>> Verify Container & Customs Clearance)
        VerificationResult verification = verifyContainer(containerNumber, sealNumber, bookingNumber);

        Optional<Appointment> appOpt = (bookingNumber != null && !bookingNumber.trim().isEmpty())
                ? appointmentRepository.findByBookingNumber(bookingNumber.trim())
                : appointmentRepository.findByContainerNumber(containerNumber.trim().toUpperCase());

        String effectiveCustomsCode = (customsClearanceCode != null && !customsClearanceCode.trim().isEmpty())
                ? customsClearanceCode.trim().toUpperCase()
                : appOpt.map(Appointment::getCustomsClearanceCode).orElse(null);

        if (effectiveCustomsCode == null || effectiveCustomsCode.trim().isEmpty()) {
            throw new IllegalStateException("Từ chối qua cổng (Gate In): Tài xế / Container chưa có Giấy thông quan của Hải quan!");
        }

        // 2. Tìm hoặc khởi tạo container
        Container container = containerRepository.findByContainerNumber(containerNumber.trim().toUpperCase())
                .orElseGet(() -> Container.builder()
                        .containerNumber(containerNumber.trim().toUpperCase())
                        .containerType(containerType != null ? containerType : Container.ContainerType.DRY)
                        .cargoStatus(cargoStatus != null ? cargoStatus : Container.CargoStatus.FULL)
                        .condition(condition != null ? condition : Container.Condition.GOOD)
                        .sealNumber(sealNumber)
                        .isoType("40HC")
                        .build());

        container.setSealNumber(sealNumber);
        container.setCondition(condition != null ? condition : Container.Condition.GOOD);
        container.setCargoStatus(cargoStatus != null ? cargoStatus : Container.CargoStatus.FULL);
        container.setCurrentStatus(Container.TrackingStatus.GATE_IN);
        container.setGateInTime(LocalDateTime.now());
        container.setCustomsClearanceCode(effectiveCustomsCode);
        container.setCustomsCleared(true);
        final Container savedContainer = containerRepository.save(container);

        // 3. Cập nhật lịch hẹn nếu có
        appOpt.ifPresent(app -> {
            app.setStatus(Appointment.AppointmentStatus.CHECKED_IN);
            app.setContainer(savedContainer);
            app.setCustomsClearanceCode(effectiveCustomsCode);
            app.setCustomsCleared(true);
            appointmentRepository.save(app);
        });

        // 4. Tự động ghi nhật ký di chuyển (<<include>> Record Container Movement)
        MovementHistory movement = MovementHistory.builder()
                .container(savedContainer)
                .eventType(MovementHistory.MovementType.GATE_IN)
                .fromLocation("Cổng tiếp nhận vào (Gate-In)")
                .toLocation("Bãi chờ hạ container")
                .truckPlate(licensePlate)
                .operatorName(operatorName != null ? operatorName : "Gate Operator")
                .timestamp(LocalDateTime.now())
                .notes("Xe " + licensePlate + " vào cổng thành công. Seal: " + sealNumber + ". Giấy thông quan HQ: " + effectiveCustomsCode + ". " + verification.getMessage())
                .build();
        movementHistoryRepository.save(movement);

        return container;
    }

    /**
     * Use Case chính: Confirm Gate Out
     * <<include>> Verify Container & Customs Clearance
     * <<include>> Record Container Movement
     */
    @Transactional
    public Container confirmGateOut(String containerNumber,
                                    String sealNumber,
                                    Container.Condition condition,
                                    String licensePlate,
                                    String customsClearanceCode,
                                    String operatorName) {

        Container container = containerRepository.findByContainerNumber(containerNumber.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy container: " + containerNumber));

        Optional<Appointment> appOpt = appointmentRepository.findByContainerNumber(container.getContainerNumber());

        String effectiveCustomsCode = (customsClearanceCode != null && !customsClearanceCode.trim().isEmpty())
                ? customsClearanceCode.trim().toUpperCase()
                : (container.getCustomsClearanceCode() != null && !container.getCustomsClearanceCode().trim().isEmpty()
                    ? container.getCustomsClearanceCode()
                    : appOpt.map(Appointment::getCustomsClearanceCode).orElse(null));

        if (effectiveCustomsCode == null || effectiveCustomsCode.trim().isEmpty() || Boolean.FALSE.equals(container.getCustomsCleared())) {
            throw new IllegalStateException("Từ chối xuất cổng (Gate Out): Tài xế / Container " + container.getContainerNumber() + " chưa có Giấy thông quan hợp lệ của Hải quan!");
        }

        // 1. Giải phóng ô bãi nếu container đang ở trong bãi
        Optional<YardSlot> slotOpt = yardSlotRepository.findByContainer(container);
        String fromLoc = "Khu vực bãi";
        if (slotOpt.isPresent()) {
            YardSlot slot = slotOpt.get();
            fromLoc = "Ô bãi " + slot.getSlotCode();
            slot.setContainer(null);
            yardSlotRepository.save(slot);
        }

        // 2. Cập nhật trạng thái container
        container.setCurrentStatus(Container.TrackingStatus.GATE_OUT);
        container.setGateOutTime(LocalDateTime.now());
        container.setCondition(condition != null ? condition : container.getCondition());
        container.setCustomsClearanceCode(effectiveCustomsCode);
        container.setCustomsCleared(true);
        containerRepository.save(container);

        // 3. Cập nhật lịch hẹn nếu có
        appOpt.ifPresent(app -> {
            app.setStatus(Appointment.AppointmentStatus.COMPLETED);
            app.setCustomsClearanceCode(effectiveCustomsCode);
            app.setCustomsCleared(true);
            appointmentRepository.save(app);
        });

        // 4. Ghi nhật ký di chuyển (<<include>> Record Container Movement)
        MovementHistory movement = MovementHistory.builder()
                .container(container)
                .eventType(MovementHistory.MovementType.GATE_OUT)
                .fromLocation(fromLoc)
                .toLocation("Rời cảng (Gate-Out)")
                .truckPlate(licensePlate)
                .operatorName(operatorName != null ? operatorName : "Gate Operator")
                .timestamp(LocalDateTime.now())
                .notes("Xe " + licensePlate + " nhận cont xuất cổng. Seal: " + sealNumber + ". Đã kiểm tra Giấy thông quan HQ: " + effectiveCustomsCode)
                .build();
        movementHistoryRepository.save(movement);

        return container;
    }

    @lombok.Value
    public static class VerificationResult {
        boolean valid;
        String message;
    }
}
