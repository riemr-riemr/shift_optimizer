package io.github.riemr.shift.presentation.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    // ルートへのアクセスはシフト画面へ転送（未ログインならSecurityが/loginへリダイレクト）
    @GetMapping("/")
    public String root() {
        return "redirect:/shift";
    }
}

