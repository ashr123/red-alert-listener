package io.github.ashr123.red.alert;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.util.StdConverter;
import org.apache.logging.log4j.Level;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

public record Configuration(boolean isMakeSound,
							boolean isAlertAll,
							boolean isDisplayResponse,
							boolean isDisplayUntranslatedDistricts,
							boolean isShowTestAlerts,
							Duration timeout,
							LanguageCode languageCode,
							Level logLevel,
							@JsonDeserialize(converter = StringsSetInternDeserializer.class)
							Set<String> districtsOfInterest) {
	private static class StringsSetInternDeserializer extends StdConverter<Set<String>, Set<String>> {
		@Override
		public Set<String> convert(Set<String> value) {
			return value.stream()
					.map(String::intern)
					.collect(Collectors.toSet());
		}
	}
}
