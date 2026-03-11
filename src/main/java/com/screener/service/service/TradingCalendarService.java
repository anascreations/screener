package com.screener.service.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TradingCalendarService {
	private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(MonthDay.of(1, 1), // New Year's Day
			MonthDay.of(2, 1), // Federal Territory Day
			MonthDay.of(5, 1), // Labour Day
			MonthDay.of(6, 5), // Yang di-Pertuan Agong's Birthday (first Monday of June — approx)
			MonthDay.of(8, 31), // National Day (Merdeka)
			MonthDay.of(9, 16), // Malaysia Day
			MonthDay.of(12, 25) // Christmas Day
	);
	private Set<LocalDate> extraHolidays = Set.of();

	public void setExtraHolidays(Set<LocalDate> dates) {
		this.extraHolidays = Set.copyOf(dates);
		log.info("Loaded {} extra holidays", dates.size());
	}

	public LocalDate nextTradingDay(LocalDate from) {
		LocalDate d = from.plusDays(1);
		while (!isTradingDay(d))
			d = d.plusDays(1);
		return d;
	}

	public boolean isTradingDay(LocalDate date) {
		DayOfWeek dow = date.getDayOfWeek();
		if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
			return false;
		if (extraHolidays.contains(date))
			return false;
		if (FIXED_HOLIDAYS.contains(MonthDay.from(date)))
			return false;
		return true;
	}

	public LocalDate lastTradingDay(LocalDate date) {
		LocalDate d = date;
		while (!isTradingDay(d))
			d = d.minusDays(1);
		return d;
	}
}
