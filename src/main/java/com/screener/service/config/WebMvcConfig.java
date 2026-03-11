package com.screener.service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
	@Value("${screener.api.base-url:http://localhost:8080}")
	private String apiBaseUrl;

	// ── Static resources ──────────────────────────────────────────────────
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/").setCachePeriod(3600);
	}

	// ── Redirect root → /ui ───────────────────────────────────────────────
	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addRedirectViewController("/", "/ui");
	}

	// ── WebClient for calling own REST API ───────────────────────────────
	@Bean
	public WebClient screenerWebClient() {
		return WebClient.builder().baseUrl(apiBaseUrl).codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024)) // 5
																														// MB
				.build();
	}
}
