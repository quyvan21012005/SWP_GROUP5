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
                                         String notes) {
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
                            .build());

            if (sealNumber != null && !sealNumber.trim().isEmpty()) {
                linkedContainer.setSealNumber(sealNumber.trim().toUpperCase());
            }
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

        String fullNotes = (notes != null && !notes.trim().isEmpty()) ? notes.trim() : "";
        if (!assignedSlotCode.isEmpty()) {
            fullNotes = (fullNotes.isEmpty() ? "" : fullNotes + " | ") + "Ô bãi chỉ định: " + assignedSlotCode;
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
                .notes(fullNotes)
                .status(Appointment.AppointmentStatus.CONFIRMED)
                .build();

        return appointmentRepository.save(appointment);
    }
}
