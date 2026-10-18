package com.ctms.controller;

import com.ctms.model.Appointment;
import com.ctms.model.Container;
import com.ctms.model.User;
import com.ctms.model.Vessel;
import com.ctms.service.AppointmentService;
import com.ctms.service.BerthService;
import com.ctms.service.ContainerService;
import com.ctms.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
@RequestMapping("/portal")
@Slf4j
public class PortalController {

    private final UserService userService;
    private final ContainerService containerService;
    private final BerthService berthService;
    private final AppointmentService appointmentService;

    public PortalController(UserService userService, ContainerService containerService,
                            BerthService berthService, AppointmentService appointmentService) {
        this.userService = userService;
        this.containerService = containerService;
        this.berthService = berthService;
        this.appointmentService = appointmentService;
    }

    /**
     * Use Case 4: View Own Shipping Info & View Vessel Schedule (Dành cho Shipping Line)
     */
    @GetMapping("/shipping-line")
    public String shippingLinePortal(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
        model.addAttribute("currentUser", currentUser);

        Long shippingLineId = (currentUser != null && currentUser.getShippingLine() != null)
                ? currentUser.getShippingLine().getId() : 1L; // Mặc định là hãng tàu Maersk nếu tài khoản quản lý xem

        List<Container> myContainers = containerService.findByShippingLine(shippingLineId);
        List<Vessel> myVessels = berthService.getVesselsByShippingLine(shippingLineId);

        model.addAttribute("myContainers", myContainers);
        model.addAttribute("myVessels", myVessels);
        model.addAttribute("allSchedules", berthService.getAllVessels());

        return "portal/shipping-line";
    }

    /**
     * Use Case 4: Book Appointment & View Gate Appointments (Dành cho Truck Driver)
     */
    @GetMapping("/driver")
    public String driverPortal(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
        model.addAttribute("currentUser", currentUser);

        List<Appointment> myAppointments;
        if (currentUser != null && currentUser.getRole() == com.ctms.model.Role.ROLE_TRUCK_DRIVER) {
            myAppointments = appointmentService.getAppointmentsForDriver(currentUser.getId());
        } else {
            myAppointments = appointmentService.getAllAppointments();
        }
        model.addAttribute("myAppointments", myAppointments);

        return "portal/truck-driver";
    }

    @PostMapping("/driver/book-appointment")
    public String bookAppointment(@RequestParam("appointmentType") Appointment.AppointmentType type,
                                  @RequestParam("bookingNumber") String bookingNumber,
                                  @RequestParam("containerNumber") String containerNumber,
                                  @RequestParam("licensePlate") String licensePlate,
                                  @RequestParam("phone") String phone,
                                  @RequestParam("appointmentTime") String appointmentTimeStr,
                                  @RequestParam(value = "sealNumber", required = false) String sealNumber,
                                  @RequestParam(value = "notes", required = false) String notes,
                                  @AuthenticationPrincipal UserDetails userDetails,
                                  RedirectAttributes redirectAttributes) {
        try {
            User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
            if (currentUser == null) {
                currentUser = userService.findByUsername("driver");
            }

            // Xử lý linh hoạt định dạng thời gian từ thẻ input datetime-local
            LocalDateTime parsedTime;
            try {
                if (appointmentTimeStr != null && appointmentTimeStr.trim().length() == 16) {
                    parsedTime = LocalDateTime.parse(appointmentTimeStr.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                } else if (appointmentTimeStr != null && appointmentTimeStr.trim().length() >= 19) {
                    parsedTime = LocalDateTime.parse(appointmentTimeStr.trim().substring(0, 19));
                } else {
                    parsedTime = LocalDateTime.now().plusHours(2);
                }
            } catch (Exception parseEx) {
                log.warn("Không parse được appointmentTime: {}, dùng mặc định +2h", appointmentTimeStr);
                parsedTime = LocalDateTime.now().plusHours(2);
            }

            String cleanBooking = bookingNumber != null ? bookingNumber.trim().toUpperCase() : "BK-" + System.currentTimeMillis();
            String cleanContainer = containerNumber != null ? containerNumber.trim().toUpperCase() : "";
            String cleanPlate = licensePlate != null ? licensePlate.trim().toUpperCase() : "";
            String cleanPhone = phone != null ? phone.trim() : "";
            String cleanSeal = (sealNumber != null && !sealNumber.trim().isEmpty()) ? sealNumber.trim().toUpperCase() : null;
            String cleanNotes = (notes != null && !notes.trim().isEmpty()) ? notes.trim() : null;

            Appointment savedAppointment = appointmentService.createAppointment(
                    currentUser, type, cleanBooking, cleanContainer, cleanPlate, cleanPhone, parsedTime, cleanSeal, cleanNotes
            );

            String slotCode = (savedAppointment.getContainer() != null && savedAppointment.getContainer().getCurrentSlot() != null)
                    ? savedAppointment.getContainer().getCurrentSlot().getSlotCode() : "Chờ xếp bãi";

            // Mức phí nâng hạ theo quy định cảng
            String feeStr = (type == Appointment.AppointmentType.IMPORT_PICKUP) ? "450.000" : "400.000";
            int amount = (type == Appointment.AppointmentType.IMPORT_PICKUP) ? 450000 : 400000;

            // Link tạo mã VietQR MBBank chuẩn xác
            String qrUrl = "https://img.vietqr.io/image/MB-9999888868-compact2.png?amount=" + amount
                    + "&addInfo=" + URLEncoder.encode("CTMS " + savedAppointment.getBookingNumber(), StandardCharsets.UTF_8)
                    + "&accountName=CTMS%20PORT%20TERMINAL";

            redirectAttributes.addFlashAttribute("successMessage",
                    "Đăng ký lịch hẹn (" + type.getDisplayName() + ") thành công! Vị trí bãi chỉ định: " + slotCode);
            redirectAttributes.addFlashAttribute("newAppointment", savedAppointment);
            redirectAttributes.addFlashAttribute("assignedSlot", slotCode);
            redirectAttributes.addFlashAttribute("serviceFee", feeStr);
            redirectAttributes.addFlashAttribute("qrUrl", qrUrl);
        } catch (Exception e) {
            log.error("Lỗi đặt lịch hẹn: ", e);
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi đặt lịch hẹn: " + e.getMessage());
        }
        return "redirect:/portal/driver";
    }
}
