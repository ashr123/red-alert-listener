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
			null,
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
			Map.ofEntries(
					Map.entry(1, "Rocket and missile fire"),
					Map.entry(3, "Earthquake"),
					Map.entry(4, "Radiological event"),
					Map.entry(5, "Fear of a tsunami"),
					Map.entry(6, "Hostile aircraft intrusion"),
					Map.entry(7, "Hazardous Materials Event"),
					Map.entry(13, "Terrorist infiltration"),
					Map.entry(101, "Rocket and missile fire drill"),
					Map.entry(103, "Earthquake drill"),
					Map.entry(104, "Home Front Command radiologic drill"),
					Map.entry(105, "Tsunami drill"),
					Map.entry(106, "Home Front Command drill"),
					Map.entry(107, "Hazardous Materials drill"),
					Map.entry(113, "Terrorist infiltration drill")
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
			Map.ofEntries(
					Map.entry(1, "اطلاق قذائف وصواريخ"),
					Map.entry(3, "هزّة أرضية"),
					Map.entry(4, "حدث إشعاعي"),
					Map.entry(5, "تحسبا للتسونامي"),
					Map.entry(6, "اختراق طائرة معادية"),
					Map.entry(7, "حدث مواد خطرة"),
					Map.entry(13, "تسلل مخربين"),
					Map.entry(101, "تمرين اطلاق قذائف وصواريخ"),
					Map.entry(103, "تمرين هزّة أرضية"),
					Map.entry(104, "قيادة الجبهة الداخلية تدريب اشعاعي"),
					Map.entry(105, "تمرين تسونامي"),
					Map.entry(106, "قيادة الجبهة الداخلية تدريب"),
					Map.entry(107, "تمرين مواد خطرة"),
					Map.entry(113, "تمرين تسلل مخربين")
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
			Map.ofEntries(
					Map.entry(1, "Ракетный обстрел"),
					Map.entry(3, "Землетрясение"),
					Map.entry(4, "Радиоактивная опасность"),
					Map.entry(5, "Угроза цунами"),
					Map.entry(6, "Нарушение воздушного пространства"),
					Map.entry(7, "Утечка опасных веществ"),
					Map.entry(13, "Проникновение террористов"),
					Map.entry(101, "Учения по ракетному обстрелу"),
					Map.entry(103, "Учения на случай землетрясения"),
					Map.entry(104, "Командование тыла - учения при опасности применения радиологического оружия"),
					Map.entry(105, "Учения на случай цунами"),
					Map.entry(106, "Командование тыла - военные учения"),
					Map.entry(107, "Учения на случай утечки опасных веществ"),
					Map.entry(113, "Учения на случай проникновения террористов")
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
	 * Search for {@code catVsImageAudioAlertName}
	 */
	private final Map<Integer, String> titleTranslations;
	/**
	 * Search for {@code seconds:}
	 */
	private final String secondsTranslation;
	/**
	 * Search for {@code var tl}
	 */
	private final Map<Duration, String> timeTranslations;

	LanguageCode(Map<String, String> testDistrictTranslations,
				 Map<Integer, String> titleTranslations,
				 String secondsTranslation,
				 Map<Duration, String> timeTranslations) {
		this.testDistrictTranslations = testDistrictTranslations;
		this.titleTranslations = titleTranslations;
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

	public String getTitleTranslation(int categoryCode, String defaultTitleTranslation) {
		return titleTranslations == null ?
				defaultTitleTranslation :
				Option.of(titleTranslations.get(categoryCode)) instanceof Some(String titleTranslation) ?
						titleTranslation :
						defaultTitleTranslation + " (translation doesn't exist)";
	}
}
