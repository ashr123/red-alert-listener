package io.github.ashr123.red.alert;

import org.apache.logging.log4j.Level;

import java.time.Duration;
import java.util.Set;

public record Configuration(boolean isMakeSound,
							boolean isAlertAll,
							boolean isDisplayResponse,
							boolean isDisplayUntranslatedDistricts,
							boolean isShowTestAlerts,
							Duration timeout,
							LanguageCode languageCode,
							Level logLevel,
							Set<String> districtsOfInterest) {
}
