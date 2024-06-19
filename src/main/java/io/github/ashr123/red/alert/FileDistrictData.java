package io.github.ashr123.red.alert;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.util.StdConverter;

import java.time.Duration;

public record FileDistrictData(String translation,
							   @JsonProperty("protectionTimeInSeconds")
							   @JsonSerialize(converter = DurationSerializer.class)
							   @JsonDeserialize(converter = DurationDeserializer.class)
							   Duration protectionTime) {
	private static class DurationSerializer extends StdConverter<Duration, Long> {
		@Override
		public Long convert(Duration value) {
			return value.toSeconds();
		}
	}
}
