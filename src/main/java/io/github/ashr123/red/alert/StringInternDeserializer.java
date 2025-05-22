package io.github.ashr123.red.alert;

import com.fasterxml.jackson.databind.util.StdConverter;

class StringInternDeserializer extends StdConverter<String, String> {
	@Override
	public String convert(String value) {
		return value.intern();
	}
}
