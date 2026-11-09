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

    @PostMapping("/shipping-line/vessel-customs")
    public String updateVesselCustoms(@RequestParam("vesselId") Long vesselId,
                                      @RequestParam("customsClearanceCode") String customsClearanceCode,
                                      RedirectAttributes redirectAttributes) {
        try {
            Vessel v = berthService.updateVesselCustomsClearance(vesselId, customsClearanceCode, true);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã khai báo Giấy thông quan Hải quan (" + v.getCustomsClearanceCode() + ") cho tàu " + v.getName() + " thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi khai báo thông quan tàu: " + e.getMessage());
        }
        return "redirect:/portal/shipping-line";
    }

    @PostMapping("/driver/book-appointment")
    public String bookAppointment(@RequestParam("appointmentType") Appointment.AppointmentType type,
                                  @RequestParam("bookingNumber") String bookingNumber,
                                  @RequestParam("containerNumber") String containerNumber,
                                  @RequestParam(value = "containerType", defaultValue = "DRY") Container.ContainerType containerType,
                                  @RequestParam("licensePlate") String licensePlate,
                                  @RequestParam("phone") String phone,
                                  @RequestParam("appointmentTime") String appointmentTimeStr,
                                  @RequestParam("customsClearanceCode") String customsClearanceCode,
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
                    currentUser, type, cleanBooking, cleanContainer, containerType, cleanPlate, cleanPhone, parsedTime, cleanSeal, customsClearanceCode, cleanNotes
            );

            String slotCode = (savedAppointment.getContainer() != null && savedAppointment.getContainer().getCurrentSlot() != null)
                    ? savedAppointment.getContainer().getCurrentSlot().getSlotCode() : "Chờ xếp bãi";

            // Mức phí dịch vụ động (Phí nâng hạ cơ bản + Phụ phí Cont lạnh + Phí lưu bãi)
            int amount = savedAppointment.getPaymentAmount() != null
                    ? savedAppointment.getPaymentAmount()
                    : ((type == Appointment.AppointmentType.IMPORT_PICKUP) ? 450000 : 400000);
            String feeStr = String.format("%,d", amount).replace(',', '.');

            // Link tạo mã VietQR MB Bank chuẩn xác: STK 0867601156 - NGUYEN VAN QUY
            String qrUrl = "https://img.vietqr.io/image/MB-0867601156-compact2.png?amount=" + amount
                    + "&addInfo=" + URLEncoder.encode("CTMS " + savedAppointment.getBookingNumber(), StandardCharsets.UTF_8)
                    + "&accountName=NGUYEN%20VAN%20QUY";

            redirectAttributes.addFlashAttribute("successMessage",
                    "Đăng ký lịch hẹn (" + type.getDisplayName() + ") thành công! Vị trí bãi: " + slotCode + " | Tổng phí: " + feeStr + " VNĐ. Vui lòng quét mã VietQR (MB Bank - 0867601156 - NGUYEN VAN QUY) để nhận Mã QR Vào Cổng.");
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

    @PostMapping("/driver/confirm-payment")
    public String confirmDriverPayment(@RequestParam("appointmentId") Long appointmentId,
                                       RedirectAttributes redirectAttributes) {
        try {
            Appointment paidApp = appointmentService.confirmPayment(appointmentId);
            String slotCode = (paidApp.getContainer() != null && paidApp.getContainer().getCurrentSlot() != null)
                    ? paidApp.getContainer().getCurrentSlot().getSlotCode() : "Hệ thống tự động sắp xếp";
            redirectAttributes.addFlashAttribute("successMessage",
                    "Xác nhận thanh toán thành công! Đã cấp Mã QR Vào Cổng: " + paidApp.getGatePassCode() + " — Hãy xuất trình mã QR này để nhân viên cổng quét khi vào cảng.");
            redirectAttributes.addFlashAttribute("paidAppointment", paidApp);
            redirectAttributes.addFlashAttribute("assignedSlot", slotCode);
        } catch (Exception e) {
            log.error("Lỗi xác nhận thanh toán: ", e);
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi xác nhận thanh toán: " + e.getMessage());
        }
        return "redirect:/portal/driver";
    }

    @PostMapping("/driver/cancel-appointment")
    public String cancelDriverAppointment(@RequestParam("appointmentId") Long appointmentId,
                                          RedirectAttributes redirectAttributes) {
        try {
            Appointment cancelled = appointmentService.cancelAppointment(appointmentId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã hủy lịch hẹn " + cancelled.getBookingNumber() + " (Container: " + cancelled.getContainerNumber() + ") và giải phóng ô bãi thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi hủy lịch hẹn: " + e.getMessage());
        }
        return "redirect:/portal/driver";
    }

    @PostMapping("/shipping-line/register-container")
    public String registerShippingLineContainer(@RequestParam("containerNumber") String containerNumber,
                                                @RequestParam(value = "isoType", defaultValue = "40HC") String isoType,
                                                @RequestParam(value = "containerType", defaultValue = "DRY") Container.ContainerType containerType,
                                                @RequestParam(value = "cargoStatus", defaultValue = "FULL") Container.CargoStatus cargoStatus,
                                                @RequestParam(value = "sealNumber", required = false) String sealNumber,
                                                @RequestParam(value = "grossWeightKg", required = false) Double grossWeightKg,
                                                @RequestParam("customsClearanceCode") String customsClearanceCode,
                                                @AuthenticationPrincipal UserDetails userDetails,
                                                RedirectAttributes redirectAttributes) {
        try {
            User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
            Long shippingLineId = (currentUser != null && currentUser.getShippingLine() != null)
                    ? currentUser.getShippingLine().getId() : 1L;
            Container created = containerService.registerManifestContainer(
                    shippingLineId, containerNumber, isoType, containerType, cargoStatus, sealNumber, grossWeightKg, customsClearanceCode
            );
            String slotInfo = created.getCurrentSlot() != null ? " (Đã phân bổ vào Ô bãi " + created.getCurrentSlot().getSlotCode() + ")" : "";
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã khai báo Manifest Container " + created.getContainerNumber() + " kèm Giấy thông quan HQ ("
                            + created.getCustomsClearanceCode() + ") thành công" + slotInfo + "!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi khai báo Container: " + e.getMessage());
        }
        return "redirect:/portal/shipping-line";
    }
}
