package com.screener.service.config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.screener.service.client.ScreenerClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
public class WebClientConfig {
	@Bean
	ScreenerClient screenerClient(WebClient.Builder builder, @Value("${screener.api.base-url}") String baseUrl) {
		WebClient webClient = builder.baseUrl(baseUrl).build();
		HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))
				.build();
		ScreenerClient delegate = factory.createClient(ScreenerClient.class);
		return (ScreenerClient) Proxy.newProxyInstance(ScreenerClient.class.getClassLoader(),
				new Class[] { ScreenerClient.class }, new ScreenerClientSafeCallHandler(delegate));
	}

	@Slf4j
	@RequiredArgsConstructor
	static class ScreenerClientSafeCallHandler implements InvocationHandler {
		private final ScreenerClient delegate;

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			try {
				return method.invoke(delegate, args);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				log.error("ScreenerClient [{}] failed: {}", method.getName(), cause.getMessage(), cause);
				return Map.of("error", cause.getMessage() != null ? cause.getMessage() : "Unknown error");
			} catch (Exception e) {
				log.error("ScreenerClient [{}] failed: {}", method.getName(), e.getMessage(), e);
				return Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
			}
		}
	}
}
