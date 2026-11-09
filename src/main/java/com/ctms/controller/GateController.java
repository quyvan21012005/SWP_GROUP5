package com.ctms.controller;

import com.ctms.model.Container;
import com.ctms.service.AppointmentService;
import com.ctms.service.ContainerService;
import com.ctms.service.GateService;
import com.ctms.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/gate")
public class GateController {

    private final GateService gateService;
    private final AppointmentService appointmentService;
    private final ContainerService containerService;
    private final UserService userService;

    public GateController(GateService gateService, AppointmentService appointmentService,
                          ContainerService containerService, UserService userService) {
        this.gateService = gateService;
        this.appointmentService = appointmentService;
        this.containerService = containerService;
        this.userService = userService;
    }

    @GetMapping
    public String gateHome(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        if (userDetails != null) {
            model.addAttribute("currentUser", userService.findByUsername(userDetails.getUsername()));
        }
        model.addAttribute("appointments", appointmentService.getAllAppointments());
        return "gate/index";
    }

    @PostMapping("/scan-qr")
    public String scanDriverQr(@RequestParam("qrCode") String qrCode,
                               RedirectAttributes redirectAttributes) {
        var appOpt = appointmentService.findByGatePassOrBooking(qrCode);
        if (appOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Quét QR thất bại: Không tìm thấy lịch hẹn hoặc Mã QR Vào Cổng (" + qrCode + ") không hợp lệ!");
            return "redirect:/gate";
        }
        var app = appOpt.get();
        if (Boolean.FALSE.equals(app.getPaid()) || app.getGatePassCode() == null || app.getGatePassCode().isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Cảnh báo tại Cổng: Xe " + app.getLicensePlate() + " (Booking: " + app.getBookingNumber()
                            + ") CHƯA THANH TOÁN phí nâng hạ cảng! Yêu cầu tài xế thanh toán qua VietQR (NGUYEN VAN QUY - 0867601156) để lấy Mã QR Vào Cổng.");
            return "redirect:/gate";
        }
        redirectAttributes.addFlashAttribute("qrVerifiedMessage",
                "Quét Mã QR Vào Cổng thành công (" + app.getGatePassCode() + ")! Đã xác thực xe " + app.getLicensePlate()
                        + " ĐÃ THANH TOÁN & CÓ GIẤY THÔNG QUAN HQ (" + app.getCustomsClearanceCode() + ").");
        if (app.getAppointmentType() == com.ctms.model.Appointment.AppointmentType.EXPORT_DROPOFF) {
            return "redirect:/gate/in?booking=" + app.getBookingNumber();
        } else {
            return "redirect:/gate/out?booking=" + app.getBookingNumber();
        }
    }

    /**
     * Use Case: Confirm Gate In
     */
    @GetMapping("/in")
    public String gateInForm(@RequestParam(value = "booking", required = false) String booking,
                             @AuthenticationPrincipal UserDetails userDetails,
                             Model model) {
        if (userDetails != null) {
            model.addAttribute("currentUser", userService.findByUsername(userDetails.getUsername()));
        }
        model.addAttribute("bookingParam", booking);
        if (booking != null && !booking.trim().isEmpty()) {
            appointmentService.findByBookingNumber(booking.trim()).ifPresent(app -> {
                model.addAttribute("app", app);
            });
        }
        return "gate/gate-in";
    }

    @PostMapping("/in")
    public String processGateIn(@RequestParam("containerNumber") String containerNumber,
                                @RequestParam("sealNumber") String sealNumber,
                                @RequestParam(value = "containerType", defaultValue = "DRY") Container.ContainerType containerType,
                                @RequestParam(value = "cargoStatus", defaultValue = "FULL") Container.CargoStatus cargoStatus,
                                @RequestParam(value = "condition", defaultValue = "GOOD") Container.Condition condition,
                                @RequestParam(value = "bookingNumber", required = false) String bookingNumber,
                                @RequestParam("licensePlate") String licensePlate,
                                @RequestParam("customsClearanceCode") String customsClearanceCode,
                                @RequestParam(value = "gatePassCode", required = false) String gatePassCode,
                                @AuthenticationPrincipal UserDetails userDetails,
                                RedirectAttributes redirectAttributes) {
        try {
            String operatorName = userDetails != null ? userDetails.getUsername() : "Gate Operator";
            gateService.confirmGateIn(containerNumber, sealNumber, containerType, cargoStatus, condition, bookingNumber, licensePlate, customsClearanceCode, gatePassCode, operatorName);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Xác nhận xe " + licensePlate + " mang cont " + containerNumber + " (Giấy HQ: " + customsClearanceCode.toUpperCase()
                            + " | Mã QR Cổng: " + (gatePassCode != null && !gatePassCode.isBlank() ? gatePassCode.toUpperCase() : "Đã xác thực") + ") vào cổng (Gate In) thành công!");
            return "redirect:/gate";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi Gate In: " + e.getMessage());
            return "redirect:/gate/in";
        }
    }

    /**
     * Use Case: Confirm Gate Out
     */
    @GetMapping("/out")
    public String gateOutForm(@RequestParam(value = "booking", required = false) String booking,
                              @AuthenticationPrincipal UserDetails userDetails,
                              Model model) {
        if (userDetails != null) {
            model.addAttribute("currentUser", userService.findByUsername(userDetails.getUsername()));
        }
        if (booking != null && !booking.trim().isEmpty()) {
            appointmentService.findByBookingNumber(booking.trim()).ifPresent(app -> {
                model.addAttribute("app", app);
            });
        }
        model.addAttribute("inYardContainers", containerService.findAll().stream()
                .filter(c -> c.getCurrentStatus() == Container.TrackingStatus.IN_YARD)
                .toList());
        return "gate/gate-out";
    }

    @PostMapping("/out")
    public String processGateOut(@RequestParam("containerNumber") String containerNumber,
                                 @RequestParam("sealNumber") String sealNumber,
                                 @RequestParam(value = "condition", defaultValue = "GOOD") Container.Condition condition,
                                 @RequestParam("licensePlate") String licensePlate,
                                 @RequestParam("customsClearanceCode") String customsClearanceCode,
                                 @RequestParam(value = "gatePassCode", required = false) String gatePassCode,
                                 @AuthenticationPrincipal UserDetails userDetails,
                                 RedirectAttributes redirectAttributes) {
        try {
            String operatorName = userDetails != null ? userDetails.getUsername() : "Gate Operator";
            gateService.confirmGateOut(containerNumber, sealNumber, condition, licensePlate, customsClearanceCode, gatePassCode, operatorName);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Xác nhận xe " + licensePlate + " chở cont " + containerNumber + " (Giấy HQ: " + customsClearanceCode.toUpperCase()
                            + " | Mã QR Cổng: " + (gatePassCode != null && !gatePassCode.isBlank() ? gatePassCode.toUpperCase() : "Đã xác thực") + ") xuất bãi (Gate Out) thành công!");
            return "redirect:/gate";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi Gate Out: " + e.getMessage());
            return "redirect:/gate/out";
        }
    }
}
