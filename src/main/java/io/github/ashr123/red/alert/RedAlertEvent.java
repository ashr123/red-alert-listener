package io.github.ashr123.red.alert;

import java.util.List;

public record RedAlertEvent(int cat,
							List<String> data,
							String desc,
							long id,
							String title) {
}
