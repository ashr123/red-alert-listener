package io.github.ashr123.red.alert;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum CommonProtectionTimes {
	IMMEDIATELY(Duration.ZERO),
	FIFTEEN_SECONDS(Duration.ofSeconds(15)),
	THIRTY_SECONDS(Duration.ofSeconds(30)),
	FORTY_FIVE_SECONDS(Duration.ofSeconds(45)),
	ONE_MINUTE(Duration.ofSeconds(60)),
	ONE_AND_A_HALF_MINUTES(Duration.ofSeconds(90)),
	THREE_MINUTES(Duration.ofSeconds(180));

	private static final Map<Long, Duration> PROTECTION_TIMES = Stream.of(values())
			.map(CommonProtectionTimes::getDuration)
			.collect(Collectors.toConcurrentMap(Duration::toSeconds, Function.identity()));

	private final Duration duration;

	CommonProtectionTimes(Duration duration) {
		this.duration = duration;
	}

	public static Duration getProtectionTime(long seconds) {
		return PROTECTION_TIMES.computeIfAbsent(seconds, Duration::ofSeconds);
	}

	public Duration getDuration() {
		return duration;
	}
}
