package com.screener.service.util;

import java.time.LocalTime;
import java.time.ZoneId;

import lombok.experimental.UtilityClass;

@UtilityClass
public class DateUtil {
	public final String KL = "Asia/Kuala_Lumpur";
	public final String US = "America/New_York";
	public final ZoneId ZONE_ID_KL = ZoneId.of(KL);
	public final ZoneId ZONE_ID_US = ZoneId.of(US);
	public final LocalTime EOD_FINAL_KL = LocalTime.of(18, 0);
	public final LocalTime EOD_FINAL_US = LocalTime.of(17, 0);
}
