package io.github.ashr123.red.alert;

import io.github.ashr123.option.Option;
import io.github.ashr123.option.Some;

import java.time.Duration;
import java.util.Map;

/**
 * Taken from <a href=https://www.oref.org.il/Shared/ClientScripts/WarningMessages/WarningMessages.js>https://www.oref.org.il/Shared/ClientScripts/WarningMessages/WarningMessages.js</a>
 */
@SuppressWarnings("unused")
public enum LanguageCode {
	HE(
			Map.ofEntries(
					Map.entry("בדיקה", "בדיקה"),
					Map.entry("בדיקה מחזורית", "בדיקה מחזורית")
			),
			"שניות",
			Map.ofEntries(
					Map.entry(CommonProtectionTimes.IMMEDIATELY.getDuration(), "מיידי"),
					Map.entry(CommonProtectionTimes.ONE_MINUTE.getDuration(), "דקה"),
					Map.entry(CommonProtectionTimes.ONE_AND_A_HALF_MINUTES.getDuration(), "דקה וחצי"),
					Map.entry(CommonProtectionTimes.THREE_MINUTES.getDuration(), "3 דקות")
			)
	),
	EN(
			Map.ofEntries(
					Map.entry("בדיקה", "Test"),
					Map.entry("בדיקה מחזורית", "Periodic Test")
			),
			"seconds",
			Map.ofEntries(
					Map.entry(CommonProtectionTimes.IMMEDIATELY.getDuration(), "Immediately"),
					Map.entry(CommonProtectionTimes.ONE_MINUTE.getDuration(), "1 minute"),
					Map.entry(CommonProtectionTimes.ONE_AND_A_HALF_MINUTES.getDuration(), "1.5 minutes"),
					Map.entry(CommonProtectionTimes.THREE_MINUTES.getDuration(), "3 minutes")
			)
	),
	AR(
			Map.ofEntries(
					Map.entry("בדיקה", "فحص"),
					Map.entry("בדיקה מחזורית", "فحص الدوري")
			),
			"ثواني",
			Map.ofEntries(
					Map.entry(CommonProtectionTimes.IMMEDIATELY.getDuration(), "بشكل فوري"),
					Map.entry(CommonProtectionTimes.ONE_MINUTE.getDuration(), "دقيقة"),
					Map.entry(CommonProtectionTimes.ONE_AND_A_HALF_MINUTES.getDuration(), "دقيقة ونصف"),
					Map.entry(CommonProtectionTimes.THREE_MINUTES.getDuration(), "3 دقائق")
			)
	),
	RU(
			Map.ofEntries(
					Map.entry("בדיקה", "Проверка"),
					Map.entry("בדיקה מחזורית", "Периодическая Проверка")
			),
			"секунды",
			Map.ofEntries(
					Map.entry(CommonProtectionTimes.IMMEDIATELY.getDuration(), "Hемедленно"),
					Map.entry(CommonProtectionTimes.ONE_MINUTE.getDuration(), "1 минут"),
					Map.entry(CommonProtectionTimes.ONE_AND_A_HALF_MINUTES.getDuration(), "1,5 минуты"),
					Map.entry(CommonProtectionTimes.THREE_MINUTES.getDuration(), "3 минуты")
			)
	);
	private final Map<String, String> testDistrictTranslations;
	/**
	 * Search for {@code seconds:}
	 */
	private final String secondsTranslation;
	/**
	 * Search for {@code var tl}
	 */
	private final Map<Duration, String> timeTranslations;

	LanguageCode(Map<String, String> testDistrictTranslations,
				 String secondsTranslation,
				 Map<Duration, String> timeTranslations) {
		this.testDistrictTranslations = testDistrictTranslations;
		this.secondsTranslation = secondsTranslation;
		this.timeTranslations = timeTranslations;
	}

	public String getTimeTranslation(Duration time) {
		return Option.of(timeTranslations.get(time)) instanceof Some(String timeTranslation) ?
				timeTranslation :
				time.toSeconds() + " " + secondsTranslation;
	}

	public boolean containsTestKey(String key) {
		return testDistrictTranslations.containsKey(key);
	}

	public String getTestTranslation(String key) {
		return testDistrictTranslations.get(key);
	}
}
