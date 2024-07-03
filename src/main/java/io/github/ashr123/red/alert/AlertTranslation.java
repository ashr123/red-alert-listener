package io.github.ashr123.red.alert;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.util.StdConverter;

import java.util.regex.Pattern;

public record AlertTranslation(@JsonDeserialize(converter = AlertDescriptionDeserializer.class) String heb,
                               @JsonDeserialize(converter = AlertDescriptionDeserializer.class) String eng,
                               @JsonDeserialize(converter = AlertDescriptionDeserializer.class) String rus,
                               @JsonDeserialize(converter = AlertDescriptionDeserializer.class) String arb,
                               int catId,
                               int matrixCatId,
                               String hebTitle,
                               String engTitle,
                               String rusTitle,
                               String arbTitle) {
    public String getAlertTitle(LanguageCode languageCode) {
        return switch (languageCode) {
            case HE -> hebTitle;
            case EN -> engTitle;
            case RU -> rusTitle;
            case AR -> arbTitle;
        };
    }

    public String getAlertText(LanguageCode languageCode) {
        return switch (languageCode) {
            case HE -> heb;
            case EN -> eng;
            case RU -> rus;
            case AR -> arb;
        };
    }

    private static class AlertDescriptionDeserializer extends StdConverter<String, String> {
        private static final Pattern PATTERN = Pattern.compile(" \\{0} \\{1},\\s*");

        @Override
        public String convert(String value) {
            final String[] split = PATTERN.split(value);
            return split[split.length - 1].strip();
        }
    }
}
