package io.github.ashr123.red.alert;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.Duration;

public record District(String label,
					   String value,
					   int id,
					   @JsonProperty("areaid")
					   int areaId,
					   @JsonProperty("areaname")
					   String areaName,
					   @JsonProperty("label_he")
					   String hebrewLabel,
					   @JsonProperty("migun_time")
					   @JsonDeserialize(converter = DurationDeserializer.class)
					   Duration protectionTime) {
}
