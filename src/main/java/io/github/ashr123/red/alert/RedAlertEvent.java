package io.github.ashr123.red.alert;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

public record RedAlertEvent(int cat,
							List<String> data,
							@JsonDeserialize(converter = StringInternDeserializer.class)
							String desc,
							long id,
							@JsonDeserialize(converter = StringInternDeserializer.class)
							String title) {
}
