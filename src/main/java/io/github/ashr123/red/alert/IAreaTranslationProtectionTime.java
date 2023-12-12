package io.github.ashr123.red.alert;

public sealed interface IAreaTranslationProtectionTime permits AreaTranslationProtectionTime, MissingAreaTranslationProtectionTime {
	String translation();
}
