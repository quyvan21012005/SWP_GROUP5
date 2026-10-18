package com.ctms.controller;

import com.ctms.model.Container;
import com.ctms.service.ContainerService;
import com.ctms.service.ShippingLineService;
import com.ctms.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/containers")
@RequiredArgsConstructor
public class ContainerController {

    private final ContainerService containerService;
    private final ShippingLineService shippingLineService;
    private final UserService userService;

    /**
     * Use Case: Search & Filter Containers
     */
    @GetMapping
    public String listContainers(@RequestParam(value = "query", required = false) String query,
                                 @RequestParam(value = "containerType", required = false) Container.ContainerType containerType,
                                 @RequestParam(value = "cargoStatus", required = false) Container.CargoStatus cargoStatus,
                                 @RequestParam(value = "condition", required = false) Container.Condition condition,
                                 @RequestParam(value = "currentStatus", required = false) Container.TrackingStatus currentStatus,
                                 @RequestParam(value = "shippingLineId", required = false) Long shippingLineId,
                                 @AuthenticationPrincipal UserDetails userDetails,
                                 Model model) {
        if (userDetails != null) {
            model.addAttribute("currentUser", userService.findByUsername(userDetails.getUsername()));
        }

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

    /**
     * Use Case: View Movement History & Container Details
     */
    @GetMapping("/{id}")
    public String containerDetail(@PathVariable("id") Long id,
                                  @AuthenticationPrincipal UserDetails userDetails,
                                  Model model) {
        if (userDetails != null) {
            model.addAttribute("currentUser", userService.findByUsername(userDetails.getUsername()));
        }

        Container container = containerService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy container ID: " + id));

        model.addAttribute("container", container);
        model.addAttribute("movements", containerService.getMovementHistory(id));
        return "container/detail";
    }
}
