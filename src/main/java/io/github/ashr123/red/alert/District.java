package io.github.ashr123.red.alert;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.Duration;

public record District(String label,
					   String value,
					   int id,
					   int areaid,
					   String areaname,
					   String label_he,
					   @JsonDeserialize(converter = DurationDeserializer.class)
					   Duration migun_time) {
}
