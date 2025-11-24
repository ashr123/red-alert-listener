package io.github.ashr123.red.alert;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.util.StdConverter;

import java.time.Duration;

public record District(@JsonDeserialize(converter = StringInternDeserializer.class)
					   String label,
					   @JsonDeserialize(converter = StringInternDeserializer.class)
					   String value,
					   int id,
					   @JsonProperty("areaid")
					   int areaId,
					   @JsonProperty("areaname")
					   @JsonDeserialize(converter = StringInternDeserializer.class)
					   String areaName,
					   @JsonProperty("label_he")
					   @JsonDeserialize(converter = StringInternDeserializer.class)
					   String hebrewLabel,
					   @JsonProperty("migun_time")
					   @JsonDeserialize(converter = ProtectionTimeDeserializer.class)
					   Duration protectionTime) {
	private static class ProtectionTimeDeserializer extends StdConverter<Long, Duration> {
		@Override
		public Duration convert(Long value) {
			return CommonProtectionTime.getProtectionTime(value);
		}
	}
}
