package com.ctms.controller;

import com.ctms.model.Appointment;
import com.ctms.model.Container;
import com.ctms.model.User;
import com.ctms.model.Vessel;
import com.ctms.service.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Luồng 1: Phân hệ Quản lý Cảng (Manager Portal - /manager/**)
 * Dành riêng cho Actor: Port Manager (Quản lý cấp cao)
 * Nghiệp vụ: Xem KPI tổng quan, phân tích hiệu suất bãi/cầu bến, quản trị hãng tàu đối tác, báo cáo doanh thu & sản lượng.
 */
@Controller
@RequestMapping("/manager")
public class ManagerController {

    private final DashboardService dashboardService;
    private final ShippingLineService shippingLineService;
    private final BerthService berthService;
    private final ContainerService containerService;
    private final AppointmentService appointmentService;
    private final YardService yardService;
    private final UserService userService;

    public ManagerController(DashboardService dashboardService,
                             ShippingLineService shippingLineService,
                             BerthService berthService,
                             ContainerService containerService,
                             AppointmentService appointmentService,
                             YardService yardService,
                             UserService userService) {
        this.dashboardService = dashboardService;
        this.shippingLineService = shippingLineService;
        this.berthService = berthService;
        this.containerService = containerService;
        this.appointmentService = appointmentService;
        this.yardService = yardService;
        this.userService = userService;
    }

    /**
     * Dashboard Quản Lý Cảng
     */
    @GetMapping({"", "/dashboard"})
    public String dashboard(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("stats", dashboardService.getStats());
        model.addAttribute("shippingLines", shippingLineService.getAllShippingLines());
        model.addAttribute("berths", berthService.getAllBerths());
        model.addAttribute("vessels", berthService.getAllVessels());
        model.addAttribute("appointments", appointmentService.getAllAppointments());
        return "manager/dashboard";
    }

    /**
     * Báo Cáo Hiệu Suất & Doanh Thu Cảng
     */
    @GetMapping("/reports")
    public String reports(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("stats", dashboardService.getStats());
        model.addAttribute("allContainers", containerService.findAll());
        model.addAttribute("allAppointments", appointmentService.getAllAppointments());
        model.addAttribute("shippingLines", shippingLineService.getAllShippingLines());
        return "manager/reports";
    }

    /**
     * Xuất Báo Cáo Doanh Thu & Lịch Hẹn Cổng ra file CSV (Hỗ trợ mở trực tiếp bằng Microsoft Excel UTF-8 BOM)
     */
    @GetMapping("/reports/export-csv")
    public void exportReportsCsv(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"CTMS_Bao_Cao_Doanh_Thu_Va_Cong.csv\"");

        List<Appointment> appointments = appointmentService.getAllAppointments();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8))) {
            // UTF-8 BOM để Microsoft Excel đọc đúng tiếng Việt có dấu
            writer.write('\uFEFF');
            writer.println("Ma Booking,Loai Nghiep Vu,So Container,Bien So Xe,Tai Xe,Giay Thong Quan HQ,Ma QR Vao Cong,Phi Dich Vu (VND),Trang Thai Thanh Toan,Trang Thai Lich Hen");

            for (Appointment a : appointments) {
                long fee = a.getPaymentAmount() != null ? a.getPaymentAmount()
                        : (a.getAppointmentType() == Appointment.AppointmentType.IMPORT_PICKUP ? 450000L : 400000L);
                String paidStr = Boolean.TRUE.equals(a.getPaid()) ? "DA THANH TOAN (VietQR)" : "CHUA THANH TOAN";
                String driverName = a.getDriver() != null ? a.getDriver().getFullName() : "N/A";
                String gatePass = a.getGatePassCode() != null ? a.getGatePassCode() : "--";
                String customs = a.getCustomsClearanceCode() != null ? a.getCustomsClearanceCode() : "--";

                writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%d,\"%s\",\"%s\"%n",
                        a.getBookingNumber(),
                        a.getAppointmentType().getDisplayName(),
                        a.getContainerNumber(),
                        a.getLicensePlate(),
                        driverName,
                        customs,
                        gatePass,
                        fee,
                        paidStr,
                        a.getStatus().getDisplayName()
                );
            }
            writer.flush();
        }
    }

    /**
     * Quản lý Danh mục Hãng tàu (Tích hợp trong luồng Manager)
     */
    @GetMapping("/shipping-lines")
    public String shippingLines(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("shippingLines", shippingLineService.getAllShippingLines());
        return "vessel/shipping-lines";
    }

    /**
     * Quản lý Cầu bến & Lịch tàu cập cảng
     */
    @GetMapping("/berths")
    public String berths(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("berths", berthService.getAllBerths());
        model.addAttribute("vessels", berthService.getAllVessels());
        model.addAttribute("scheduledVessels", berthService.getAllVessels().stream()
                .filter(v -> v.getStatus() == Vessel.VesselStatus.SCHEDULED)
                .toList());
        return "vessel/berth-overview";
    }

    /**
     * Tra cứu & Giám sát Container toàn Cảng
     */
    @GetMapping("/containers")
    public String containers(@RequestParam(value = "query", required = false) String query,
                             @RequestParam(value = "containerType", required = false) Container.ContainerType containerType,
                             @RequestParam(value = "cargoStatus", required = false) Container.CargoStatus cargoStatus,
                             @RequestParam(value = "condition", required = false) Container.Condition condition,
                             @RequestParam(value = "currentStatus", required = false) Container.TrackingStatus currentStatus,
                             @RequestParam(value = "shippingLineId", required = false) Long shippingLineId,
                             @AuthenticationPrincipal UserDetails userDetails,
                             Model model) {
        User currentUser = userDetails != null ? userService.findByUsername(userDetails.getUsername()) : null;
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("containers", containerService.search(query, containerType, cargoStatus, condition, currentStatus, shippingLineId));
        model.addAttribute("shippingLines", shippingLineService.getAllShippingLines());
        model.addAttribute("paramQuery", query);
        model.addAttribute("paramType", containerType);
        model.addAttribute("paramCargo", cargoStatus);
        model.addAttribute("paramCondition", condition);
        model.addAttribute("paramStatus", currentStatus);
        model.addAttribute("paramShippingLineId", shippingLineId);
        return "container/list";
    }
}
