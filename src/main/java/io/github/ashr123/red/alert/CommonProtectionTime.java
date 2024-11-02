package io.github.ashr123.red.alert;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum CommonProtectionTime {
	IMMEDIATELY(Duration.ZERO),
	FIFTEEN_SECONDS(Duration.ofSeconds(15)),
	THIRTY_SECONDS(Duration.ofSeconds(30)),
	FORTY_FIVE_SECONDS(Duration.ofSeconds(45)),
	ONE_MINUTE(ChronoUnit.MINUTES.getDuration()),
	ONE_AND_A_HALF_MINUTES(Duration.ofSeconds(90)),
	THREE_MINUTES(Duration.ofSeconds(180));

	private static final Logger LOGGER = LogManager.getLogger();
	private static final Map<Long, Duration> PROTECTION_TIMES = Stream.of(values())
			.map(CommonProtectionTime::getDuration)
			.collect(Collectors.toConcurrentMap(Duration::toSeconds, Function.identity()));

	private final Duration duration;

	CommonProtectionTime(Duration duration) {
		this.duration = duration;
	}

	public static Duration getProtectionTime(long seconds) {
		return PROTECTION_TIMES.computeIfAbsent(
				seconds,
				key -> {
					LOGGER.warn("Got uncommon protection time for {} seconds!", key);
					return Duration.ofSeconds(key);
				}
		);
	}

	public Duration getDuration() {
		return duration;
	}
}
