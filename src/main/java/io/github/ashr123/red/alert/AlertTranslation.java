package io.github.ashr123.red.alert;

public record AlertTranslation(String heb,
                               String eng,
                               String rus,
                               String arb,
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
}
