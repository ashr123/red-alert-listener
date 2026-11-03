package io.github.ashr123.red.alert;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.util.StdConverter;

import java.util.regex.Pattern;

public record AlertTranslations(@JsonDeserialize(converter = AlertDescriptionDeserializer.class)
								String heb,
								@JsonDeserialize(converter = AlertDescriptionDeserializer.class)
								String eng,
								@JsonDeserialize(converter = AlertDescriptionDeserializer.class)
								String rus,
								@JsonDeserialize(converter = AlertDescriptionDeserializer.class)
								String arb,
								int catId,
								int matrixCatId,
								@JsonDeserialize(converter = StringInternDeserializer.class)
								String hebTitle,
								@JsonDeserialize(converter = StringInternDeserializer.class)
								String engTitle,
								@JsonDeserialize(converter = StringInternDeserializer.class)
								String rusTitle,
								@JsonDeserialize(converter = StringInternDeserializer.class)
								String arbTitle,
								@JsonDeserialize(converter = StringInternDeserializer.class)
								String updateType) {
	public String getAlertTitle(LanguageCode languageCode) {
		return switch (languageCode) {
			case HEB -> hebTitle;
			case ENG -> engTitle;
			case RUS -> rusTitle;
			case ARB -> arbTitle;
		};
	}

	public String getAlertText(LanguageCode languageCode) {
		return switch (languageCode) {
			case HEB -> heb;
			case ENG -> eng;
			case RUS -> rus;
			case ARB -> arb;
		};
	}

	private static class AlertDescriptionDeserializer extends StdConverter<String, String> {
		private static final Pattern PATTERN = Pattern.compile("^.*\\{0} \\{1},\\s*");

		@Override
		public String convert(String value) {
			final String[] split = PATTERN.split(value.intern());
			return split[split.length - 1].strip().intern();
		}
	}
}
