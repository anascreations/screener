package com.screener.service.service;

public interface SessionProvider {
	String getSessionCookie();

	String getCrumb();

	void forceRefresh();
}