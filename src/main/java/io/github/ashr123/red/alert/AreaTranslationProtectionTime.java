package io.github.ashr123.red.alert;

import java.util.Objects;

public record AreaTranslationProtectionTime(String translatedAreaName,
											String translation,
											int protectionTimeInSeconds) implements IAreaTranslationProtectionTime {
	@Override
	public boolean equals(Object o) {
		return this == o ||
				o instanceof AreaTranslationProtectionTime that &&
						Objects.equals(translation, that.translation);
	}

	@Override
	public int hashCode() {
		return Objects.hash(translation);
	}
}
