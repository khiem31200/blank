package com.example.blank.controller;

import com.example.blank.entity.Identity;
import com.example.blank.entity.RecognitionLog;
import com.example.blank.repository.IdentityRepository;
import com.example.blank.repository.RecognitionLogRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class Home {

    private final IdentityRepository identityRepository;
    private final RecognitionLogRepository recognitionLogRepository;

    public Home(IdentityRepository identityRepository,
                RecognitionLogRepository recognitionLogRepository) {
        this.identityRepository = identityRepository;
        this.recognitionLogRepository = recognitionLogRepository;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/view/registers";
    }

    @GetMapping("/view/registers")
    public String registerList(Model model) {
        model.addAttribute("identities",
                identityRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")));
        return "registers";
    }

    @GetMapping("/view/registers/{id}")
    public String identityLogs(@PathVariable Integer id,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        Identity identity = identityRepository.findById(id).orElse(null);
        if (identity == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy định danh #" + id);
            return "redirect:/view/registers";
        }
        List<RecognitionLog> logs =
                recognitionLogRepository.findByIdentityNameOrderByRecognizedAtDesc(identity.getName());

        model.addAttribute("identity", identity);
        model.addAttribute("logs", logs);
        model.addAttribute("totalLogs", logs.size());
        model.addAttribute("avgSimilarity",
                logs.stream().mapToDouble(RecognitionLog::getSimilarity).average().orElse(0));
        model.addAttribute("maxSimilarity",
                logs.stream().mapToDouble(RecognitionLog::getSimilarity).max().orElse(0));
        model.addAttribute("lastSeen", logs.isEmpty() ? null : logs.get(0).getRecognizedAt());
        return "identity-logs";
    }

    @PostMapping("/view/registers/{id}/rename")
    @Transactional
    public String renameIdentity(@PathVariable Integer id,
                                 @RequestParam("newName") String newName,
                                 RedirectAttributes redirectAttributes) {
        Identity identity = identityRepository.findById(id).orElse(null);
        if (identity == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy định danh #" + id);
            return "redirect:/view/registers";
        }

        String trimmed = newName == null ? "" : newName.trim();
        String oldName = identity.getName();

        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Tên mới không được để trống.");
            return "redirect:/view/registers/" + id;
        }
        if (trimmed.equals(oldName)) {
            redirectAttributes.addFlashAttribute("error", "Tên mới trùng với tên hiện tại.");
            return "redirect:/view/registers/" + id;
        }
        if (identityRepository.existsByName(trimmed)) {
            redirectAttributes.addFlashAttribute("error",
                    "Tên \"" + trimmed + "\" đã tồn tại. Vui lòng chọn tên khác.");
            return "redirect:/view/registers/" + id;
        }

        identity.setName(trimmed);
        identityRepository.save(identity);
        int updatedLogs = recognitionLogRepository.renameIdentityName(oldName, trimmed);

        redirectAttributes.addFlashAttribute("message",
                "Đã đổi tên \"" + oldName + "\" thành \"" + trimmed + "\" (cập nhật " + updatedLogs + " bản ghi lịch sử).");
        return "redirect:/view/registers/" + id;
    }

    @PostMapping("/view/registers/{id}/delete")
    @Transactional
    public String deleteIdentity(@PathVariable Integer id,
                                 RedirectAttributes redirectAttributes) {
        Identity identity = identityRepository.findById(id).orElse(null);
        if (identity == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy định danh #" + id);
            return "redirect:/view/registers";
        }
        long deletedLogs = recognitionLogRepository.deleteByIdentityName(identity.getName());
        identityRepository.delete(identity);
        redirectAttributes.addFlashAttribute("message",
                "Đã xóa định danh \"" + identity.getName() + "\" cùng " + deletedLogs + " bản ghi lịch sử nhận diện.");
        return "redirect:/view/registers";
    }
}
