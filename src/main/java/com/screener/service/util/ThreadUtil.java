package com.screener.service.util;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class ThreadUtil {
	public void sleep(long ms) {
		if (ms <= 0)
			return;
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("[Thread] Sleep interrupted on thread: {}", Thread.currentThread().getName());
		}
	}

	public void sleep(Duration duration) {
		sleep(duration.toMillis());
	}

	public void sleepSeconds(long seconds) {
		sleep(Duration.ofSeconds(seconds));
	}

	public void sleepWithJitter(long baseMs, long maxJitterMs) {
		long jitter = ThreadLocalRandom.current().nextLong(maxJitterMs);
		sleep(baseMs + jitter);
	}

	public long backoff(int attempt) {
		return Math.min(5000L * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(2000), 30_000L);
	}

	public void sleepExponentialBackoff(int attempt, long baseMs, long maxMs) {
		long backoff = (long) (baseMs * Math.pow(2, attempt));
		long capped = Math.min(backoff, maxMs);
		long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, capped / 4));
		long actual = capped + jitter;
		log.info("[Thread] Backoff attempt={} sleeping={}ms (base={}ms, max={}ms)", attempt, actual, baseMs, maxMs);
		sleep(actual);
	}

	public boolean isInterrupted() {
		return Thread.interrupted();
	}

	public void checkInterrupted() {
		if (Thread.interrupted()) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Thread interrupted: " + Thread.currentThread().getName());
		}
	}

	public ThreadFactory namedThreadFactory(String prefix) {
		AtomicInteger counter = new AtomicInteger(0);
		return r -> {
			Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

	public ThreadFactory namedVirtualThreadFactory(String prefix) {
		AtomicInteger counter = new AtomicInteger(0);
		return r -> Thread.ofVirtual().name(prefix + "-" + counter.incrementAndGet()).unstarted(r);
	}
}