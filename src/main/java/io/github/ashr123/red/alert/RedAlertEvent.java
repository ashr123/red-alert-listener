package io.github.ashr123.red.alert;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * @param data Should be interned by {@link District#hebrewLabel()}
 */
public record RedAlertEvent(int cat,
							List<String> data,
							@JsonDeserialize(converter = StringInternDeserializer.class)
							String desc,
							long id,
							@JsonDeserialize(converter = StringInternDeserializer.class)
							String title) {
}
