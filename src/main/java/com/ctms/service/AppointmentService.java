package com.ctms.service;

import com.ctms.model.Appointment;
import com.ctms.model.Container;
import com.ctms.model.ShippingLine;
import com.ctms.model.User;
import com.ctms.model.YardSlot;
import com.ctms.repository.AppointmentRepository;
import com.ctms.repository.ContainerRepository;
import com.ctms.repository.ShippingLineRepository;
import com.ctms.repository.YardSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final ContainerRepository containerRepository;
    private final YardSlotRepository yardSlotRepository;
    private final ShippingLineRepository shippingLineRepository;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              ContainerRepository containerRepository,
                              YardSlotRepository yardSlotRepository,
                              ShippingLineRepository shippingLineRepository) {
        this.appointmentRepository = appointmentRepository;
        this.containerRepository = containerRepository;
        this.yardSlotRepository = yardSlotRepository;
        this.shippingLineRepository = shippingLineRepository;
    }

    public List<Appointment> getAllAppointments() {
        return appointmentRepository.findAllByOrderByAppointmentTimeDesc();
    }

    public List<Appointment> getAppointmentsForDriver(Long driverId) {
        return appointmentRepository.findByDriverIdOrderByAppointmentTimeDesc(driverId);
    }

    public Optional<Appointment> findById(Long id) {
        return appointmentRepository.findById(id);
    }

    public Optional<Appointment> findByBookingNumber(String bookingNumber) {
        return appointmentRepository.findByBookingNumber(bookingNumber);
    }

    @Transactional
    public Appointment createAppointment(User driver,
                                         Appointment.AppointmentType type,
                                         String bookingNumber,
                                         String containerNumber,
                                         String licensePlate,
                                         String phone,
                                         LocalDateTime appointmentTime,
                                         String sealNumber,
                                         String customsClearanceCode,
                                         String notes) {
        if (customsClearanceCode == null || customsClearanceCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Bắt buộc phải nhập Số Giấy thông quan của Hải quan để đặt lịch hẹn qua cổng!");
        }
        String cleanCustomsCode = customsClearanceCode.trim().toUpperCase();
        String cleanContainerNo = containerNumber != null ? containerNumber.toUpperCase().trim() : "CONT-" + System.currentTimeMillis();
        ShippingLine defaultShippingLine = shippingLineRepository.findAll().stream().findFirst().orElse(null);

        Container linkedContainer;
        String assignedSlotCode = "";

        if (type == Appointment.AppointmentType.EXPORT_DROPOFF) {
            // Hạ cont Xuất: Tạo container và tự động gán vào ô bãi trống đầu tiên (Pre-allocate)
            List<YardSlot> emptySlots = yardSlotRepository.findByContainerIsNull();
            YardSlot targetSlot = emptySlots.isEmpty() ? null : emptySlots.get(0);

            linkedContainer = containerRepository.findByContainerNumber(cleanContainerNo)
                    .orElseGet(() -> Container.builder()
                            .containerNumber(cleanContainerNo)
                            .isoType("40HC")
                            .containerType(Container.ContainerType.DRY)
                            .cargoStatus(Container.CargoStatus.FULL)
                            .condition(Container.Condition.GOOD)
                            .currentStatus(Container.TrackingStatus.EXPECTED)
                            .shippingLine(defaultShippingLine)
                            .sealNumber(sealNumber)
                            .customsClearanceCode(cleanCustomsCode)
                            .customsCleared(true)
                            .build());

            if (sealNumber != null && !sealNumber.trim().isEmpty()) {
                linkedContainer.setSealNumber(sealNumber.trim().toUpperCase());
            }
            linkedContainer.setCustomsClearanceCode(cleanCustomsCode);
            linkedContainer.setCustomsCleared(true);
            linkedContainer = containerRepository.save(linkedContainer);

            if (targetSlot != null) {
                targetSlot.setContainer(linkedContainer);
                linkedContainer.setCurrentSlot(targetSlot);
                yardSlotRepository.save(targetSlot);
                assignedSlotCode = targetSlot.getSlotCode();
            }
        } else {
            // Lấy cont Nhập: Tìm cont đã có trong bãi hoặc xếp vào 1 ô bãi để tài xế đến lấy
            Optional<Container> existingContainerOpt = containerRepository.findByContainerNumber(cleanContainerNo);
            if (existingContainerOpt.isPresent()) {
                linkedContainer = existingContainerOpt.get();
                linkedContainer.setCustomsClearanceCode(cleanCustomsCode);
                linkedContainer.setCustomsCleared(true);
                linkedContainer = containerRepository.save(linkedContainer);
                Optional<YardSlot> slotOpt = yardSlotRepository.findByContainer(linkedContainer);
                if (slotOpt.isPresent()) {
                    assignedSlotCode = slotOpt.get().getSlotCode();
                    linkedContainer.setCurrentSlot(slotOpt.get());
                }
            } else {
                List<YardSlot> emptySlots = yardSlotRepository.findByContainerIsNull();
                YardSlot targetSlot = emptySlots.isEmpty() ? null : emptySlots.get(0);

                linkedContainer = Container.builder()
                        .containerNumber(cleanContainerNo)
                        .isoType("40HC")
                        .containerType(Container.ContainerType.DRY)
                        .cargoStatus(Container.CargoStatus.FULL)
                        .condition(Container.Condition.GOOD)
                        .currentStatus(Container.TrackingStatus.IN_YARD)
                        .shippingLine(defaultShippingLine)
                        .sealNumber(sealNumber != null && !sealNumber.trim().isEmpty() ? sealNumber.trim().toUpperCase() : "SL-IMP-882")
                        .customsClearanceCode(cleanCustomsCode)
                        .customsCleared(true)
                        .build();
                linkedContainer = containerRepository.save(linkedContainer);

                if (targetSlot != null) {
                    targetSlot.setContainer(linkedContainer);
                    linkedContainer.setCurrentSlot(targetSlot);
                    yardSlotRepository.save(targetSlot);
                    assignedSlotCode = targetSlot.getSlotCode();
                }
            }
        }

        // Tính phí dịch vụ cảng động: Phí nâng hạ cơ bản + Phụ phí Cont lạnh (Reefer) + Phí lưu bãi quá hạn (> 2 ngày)
        int baseFee = (type == Appointment.AppointmentType.IMPORT_PICKUP) ? 450000 : 400000;
        int reeferSurcharge = (linkedContainer != null && linkedContainer.getContainerType() == Container.ContainerType.REEFER) ? 150000 : 0;
        int demurrageFee = 0;
        if (linkedContainer != null && linkedContainer.getGateInTime() != null) {
            long daysInYard = java.time.temporal.ChronoUnit.DAYS.between(linkedContainer.getGateInTime(), LocalDateTime.now());
            if (daysInYard > 2) {
                demurrageFee = (int) ((daysInYard - 2) * 50000);
            }
        }
        int feeAmount = baseFee + reeferSurcharge + demurrageFee;

        String fullNotes = (notes != null && !notes.trim().isEmpty()) ? notes.trim() : "";
        if (!assignedSlotCode.isEmpty()) {
            fullNotes = (fullNotes.isEmpty() ? "" : fullNotes + " | ") + "Ô bãi chỉ định: " + assignedSlotCode;
        }
        fullNotes = (fullNotes.isEmpty() ? "" : fullNotes + " | ") + "Giấy thông quan HQ: " + cleanCustomsCode;
        if (reeferSurcharge > 0) {
            fullNotes += " | Phụ phí Cont Lạnh: +150.000đ";
        }
        if (demurrageFee > 0) {
            fullNotes += " | Phí lưu bãi quá hạn: +" + String.format("%,d", demurrageFee).replace(',', '.') + "đ";
        }

        Appointment appointment = Appointment.builder()
                .driver(driver)
                .appointmentType(type)
                .bookingNumber(bookingNumber)
                .containerNumber(cleanContainerNo)
                .container(linkedContainer)
                .licensePlate(licensePlate.toUpperCase())
                .driverPhone(phone)
                .appointmentTime(appointmentTime)
                .sealNumber(sealNumber)
                .customsClearanceCode(cleanCustomsCode)
                .customsCleared(true)
                .paid(false)
                .paymentAmount(feeAmount)
                .gatePassCode(null)
                .notes(fullNotes)
                .status(Appointment.AppointmentStatus.CONFIRMED)
                .build();

        return appointmentRepository.save(appointment);
    }

    @Transactional
    public Appointment cancelAppointment(Long appointmentId) {
        Appointment app = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch hẹn ID: " + appointmentId));
        if (app.getStatus() == Appointment.AppointmentStatus.CHECKED_IN
                || app.getStatus() == Appointment.AppointmentStatus.COMPLETED) {
            throw new IllegalStateException("Không thể hủy lịch hẹn đã vào cổng hoặc đã hoàn tất!");
        }
        // Giải phóng ô bãi tạm giữ nếu là cont hạ xuất khẩu chưa vào cổng
        if (app.getContainer() != null && app.getContainer().getCurrentStatus() == Container.TrackingStatus.EXPECTED) {
            yardSlotRepository.findByContainer(app.getContainer()).ifPresent(slot -> {
                slot.setContainer(null);
                yardSlotRepository.save(slot);
            });
        }
        app.setStatus(Appointment.AppointmentStatus.CANCELLED);
        return appointmentRepository.save(app);
    }

    @Transactional
    public Appointment confirmPayment(Long appointmentId) {
        Appointment app = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch hẹn ID: " + appointmentId));
        app.setPaid(true);
        app.setPaidAt(LocalDateTime.now());
        if (app.getPaymentAmount() == null) {
            app.setPaymentAmount(app.getAppointmentType() == Appointment.AppointmentType.IMPORT_PICKUP ? 450000 : 400000);
        }
        String cleanBk = app.getBookingNumber() != null ? app.getBookingNumber().replaceAll("[^A-Za-z0-9]", "").toUpperCase() : "BK";
        String passCode = "QR-PASS-" + cleanBk + "-" + (1000 + app.getId());
        app.setGatePassCode(passCode);
        return appointmentRepository.save(app);
    }

    public Optional<Appointment> findByGatePassOrBooking(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            return Optional.empty();
        }
        String cleaned = rawInput.trim();
        // Nếu quét chuỗi QR đầy đủ: GATEPASS|CODE:QR-PASS-...|BOOKING:BK-...
        if (cleaned.contains("CODE:")) {
            for (String part : cleaned.split("\\|")) {
                if (part.startsWith("CODE:")) {
                    String extractedCode = part.substring(5).trim();
                    Optional<Appointment> byCode = appointmentRepository.findByGatePassCode(extractedCode);
                    if (byCode.isPresent()) return byCode;
                }
                if (part.startsWith("BOOKING:")) {
                    String extractedBk = part.substring(8).trim();
                    Optional<Appointment> byBk = appointmentRepository.findByBookingNumber(extractedBk);
                    if (byBk.isPresent()) return byBk;
                }
            }
        }
        Optional<Appointment> byPass = appointmentRepository.findByGatePassCode(cleaned.toUpperCase());
        if (byPass.isPresent()) {
            return byPass;
        }
        return appointmentRepository.findByBookingNumber(cleaned.toUpperCase());
    }
}
