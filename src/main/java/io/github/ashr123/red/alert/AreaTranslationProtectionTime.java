package io.github.ashr123.red.alert;

import java.time.Duration;

/// @param translatedAreaName should be interned by [District#areaName()]
/// @param translation should be interned by [District#label()]
public record AreaTranslationProtectionTime(String translatedAreaName,
											String translation,
											Duration protectionTime) implements IAreaTranslationProtectionTime {
}
