package com.example.blank.controller;

import com.example.blank.entity.Device;
import com.example.blank.repository.DeviceRepository;
import com.example.blank.websocket.DeviceSessionRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Trang quan ly thiet bi ESP32 da dang ky: xem trang thai + doi ten hien thi.
 * device_id la PK phan cung KHONG doi -> chi doi cot display_name.
 * Online/offline lay real-time tu registry (session WS), khong chi tin cot status DB.
 */
@Controller
public class DeviceViewController {

    private final DeviceRepository deviceRepository;
    private final DeviceSessionRegistry registry;

    public DeviceViewController(DeviceRepository deviceRepository, DeviceSessionRegistry registry) {
        this.deviceRepository = deviceRepository;
        this.registry = registry;
    }

    @GetMapping("/view/devices")
    public String deviceList(Model model) {
        model.addAttribute("devices", deviceRepository.findAllByOrderByStatusDescLastSeenDesc());
        model.addAttribute("onlineIds", registry.onlineDeviceIds()); // de danh dau online real-time
        return "devices";
    }

    @PostMapping("/view/devices/{deviceId}/rename")
    public String rename(@PathVariable String deviceId,
                         @RequestParam("displayName") String displayName,
                         RedirectAttributes ra) {
        Device d = deviceRepository.findById(deviceId).orElse(null);
        if (d == null) {
            ra.addFlashAttribute("error", "Khong tim thay thiet bi #" + deviceId);
            return "redirect:/view/devices";
        }
        String trimmed = displayName == null ? "" : displayName.trim();
        if (trimmed.length() > 100) {
            ra.addFlashAttribute("error", "Ten hien thi toi da 100 ky tu.");
            return "redirect:/view/devices";
        }
        d.setDisplayName(trimmed.isEmpty() ? null : trimmed); // de trong = xoa ten, ve lai device_id
        deviceRepository.save(d);
        ra.addFlashAttribute("message", trimmed.isEmpty()
                ? "Da xoa ten hien thi cua thiet bi " + deviceId
                : "Da doi ten thiet bi " + deviceId + " thanh \"" + trimmed + "\".");
        return "redirect:/view/devices";
    }
}
