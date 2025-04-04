package io.github.ashr123.red.alert;

import java.time.Duration;
import java.util.Map;

/**
 * @see <a href=https://www.oref.org.il/Shared/ClientScripts/WarningMessages/WarningMessages.js>https://www.oref.org.il/Shared/ClientScripts/WarningMessages/WarningMessages.js</a>
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
					Map.entry(CommonProtectionTime.IMMEDIATELY.getDuration(), "מיידי"),
					Map.entry(CommonProtectionTime.ONE_MINUTE.getDuration(), "דקה"),
					Map.entry(CommonProtectionTime.ONE_AND_A_HALF_MINUTES.getDuration(), "דקה וחצי"),
					Map.entry(CommonProtectionTime.THREE_MINUTES.getDuration(), "3 דקות")
			)
	),
	EN(
			Map.ofEntries(
					Map.entry("בדיקה", "Test"),
					Map.entry("בדיקה מחזורית", "Periodic Test")
			),
			"seconds",
			Map.ofEntries(
					Map.entry(CommonProtectionTime.IMMEDIATELY.getDuration(), "Immediately"),
					Map.entry(CommonProtectionTime.ONE_MINUTE.getDuration(), "1 minute"),
					Map.entry(CommonProtectionTime.ONE_AND_A_HALF_MINUTES.getDuration(), "1.5 minutes"),
					Map.entry(CommonProtectionTime.THREE_MINUTES.getDuration(), "3 minutes")
			)
	),
	AR(
			Map.ofEntries(
					Map.entry("בדיקה", "فحص"),
					Map.entry("בדיקה מחזורית", "فحص الدوري")
			),
			"ثواني",
			Map.ofEntries(
					Map.entry(CommonProtectionTime.IMMEDIATELY.getDuration(), "بشكل فوري"),
					Map.entry(CommonProtectionTime.ONE_MINUTE.getDuration(), "دقيقة"),
					Map.entry(CommonProtectionTime.ONE_AND_A_HALF_MINUTES.getDuration(), "دقيقة ونصف"),
					Map.entry(CommonProtectionTime.THREE_MINUTES.getDuration(), "3 دقائق")
			)
	),
	RU(
			Map.ofEntries(
					Map.entry("בדיקה", "Проверка"),
					Map.entry("בדיקה מחזורית", "Периодическая Проверка")
			),
			"секунды",
			Map.ofEntries(
					Map.entry(CommonProtectionTime.IMMEDIATELY.getDuration(), "Hемедленно"),
					Map.entry(CommonProtectionTime.ONE_MINUTE.getDuration(), "1 минут"),
					Map.entry(CommonProtectionTime.ONE_AND_A_HALF_MINUTES.getDuration(), "1,5 минуты"),
					Map.entry(CommonProtectionTime.THREE_MINUTES.getDuration(), "3 минуты")
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
		final String translation = timeTranslations.get(time);
		return translation == null ?
				time.toSeconds() + " " + secondsTranslation :
				translation;
	}

	public boolean containsTestKey(String key) {
		return testDistrictTranslations.containsKey(key);
	}

	public String getTestTranslation(String key) {
		return testDistrictTranslations.get(key);
	}
}
