package com.screener.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ScreenerApplication {
	public static void main(String[] args) {
		SpringApplication.run(ScreenerApplication.class, args);
	}
}
