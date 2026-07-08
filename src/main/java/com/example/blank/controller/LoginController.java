package com.example.blank.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class LoginController {

    @GetMapping("/")
    public String home() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String username,
                          @RequestParam String password,
                          RedirectAttributes redirectAttributes) {

        if ("admin".equals(username) && "123456".equals(password)) {
            return "redirect:/view/registers";
        }

        redirectAttributes.addFlashAttribute(
                "error",
                "Sai tài khoản hoặc mật khẩu."
        );

        return "redirect:/login";
    }

}