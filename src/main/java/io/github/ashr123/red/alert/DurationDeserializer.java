package io.github.ashr123.red.alert;

import com.fasterxml.jackson.databind.util.StdConverter;

import java.time.Duration;

class DurationDeserializer extends StdConverter<Long, Duration> {
	@Override
	public Duration convert(Long value) {
		return CommonProtectionTime.getProtectionTime(value);
	}
}
