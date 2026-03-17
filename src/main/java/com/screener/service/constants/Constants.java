package com.screener.service.constants;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Constants {
	public final String[] USER_AGENTS = {
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/123.0.0.0 Safari/537.36",
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36" };
	public final String FINANCE_YAHOO_URL = "https://finance.yahoo.com";
	public final String QUERY1_FINANCE_YAHOO_URL = "https://query1.finance.yahoo.com";
	public final String QUERY2_FINANCE_YAHOO_URL = "https://query2.finance.yahoo.com";
	public final String NASDAQ = "NMS";
	public final String NYSE = "NYQ";
	public final String NASDAQ_CM = "NCM";
	public final String NYSE_MKT = "ASE";
	public final String GSPC_IDX = "^GSPC";
	public final String KLCI_IDX = "^KLCI";
}
