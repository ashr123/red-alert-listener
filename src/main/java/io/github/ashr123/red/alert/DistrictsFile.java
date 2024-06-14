package io.github.ashr123.red.alert;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.util.StdConverter;

import java.time.Duration;

public record DistrictsFile(String translation,
							@JsonSerialize(converter = DurationSerializer.class)
							@JsonDeserialize(converter = DurationDeserializer.class)
							Duration protectionTimeInSeconds) {
	private static class DurationSerializer extends StdConverter<Duration, Long> {
		@Override
		public Long convert(Duration value) {
			return value.toSeconds();
		}
	}

	private static class DurationDeserializer extends StdConverter<Long, Duration> {
		@Override
		public Duration convert(Long value) {
			return CommonProtectionTimes.getProtectionTime(value);
		}
	}
}
