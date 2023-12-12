package io.github.ashr123.red.alert;

public record AreaTranslationProtectionTime(String translatedAreaName,
											String translation,
											int protectionTimeInSeconds) implements IAreaTranslationProtectionTime {
}
