package io.github.ashr123.red.alert;

import java.time.Duration;

/**
 *
 * @param translatedAreaName Should be interned by {@link District#areaName()}
 * @param translation Should be interned by {@link District#label()}
 */
public record AreaTranslationProtectionTime(String translatedAreaName,
											String translation,
											Duration protectionTime) implements IAreaTranslationProtectionTime {
}
