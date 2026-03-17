package com.screener.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.HtmlUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

@Configuration
public class JsonPrettyConfig {
	@Bean
	JsonPrettyPrinter jsonPretty() {
		ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		return obj -> {
			if (obj == null)
				return "";
			try {
				String json = mapper.writeValueAsString(obj);
				return HtmlUtils.htmlEscape(json);
			} catch (JsonProcessingException e) {
				return HtmlUtils.htmlEscape(obj.toString());
			}
		};
	}

	@FunctionalInterface
	public interface JsonPrettyPrinter {
		String format(Object obj);
	}
}