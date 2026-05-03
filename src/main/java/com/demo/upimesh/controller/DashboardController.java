package com.demo.upimesh.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:5500"})
public class DashboardController {

    @GetMapping("/")
    public String home() {
        return "dashboard";
    }
}
