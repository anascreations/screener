package com.screener.service.dto;

public record YahooSession(String cookie, String crumb, long fetchedAt) {
}
