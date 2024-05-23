package io.github.ashr123.red.alert;

import java.time.Duration;

public record AreaTranslationProtectionTime(String translatedAreaName,
											String translation,
											Duration protectionTime) implements IAreaTranslationProtectionTime {
}
