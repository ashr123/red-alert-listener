package io.github.ashr123.red.alert;

import io.github.ashr123.exceptional.functions.ThrowingFunction;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class ClipManager implements AutoCloseable {
	private final Clip defaultClip = AudioSystem.getClip(/*Stream.of(AudioSystem.getMixerInfo()).parallel().unordered()
				.filter(mixerInfo -> COLLATOR.equals(mixerInfo.getName(), "default [default]"))
				.findAny()
				.orElse(null)*/);
	private final Map<Integer, Clip> soundClips = new ConcurrentHashMap<>(13); // ref.alertsTranslations.size() on 9/10/2025

	public ClipManager() throws LineUnavailableException, UnsupportedAudioFileException, IOException {
		defaultClip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/sounds/alarmSound.wav")))));
	}

	/**
	 *  See https://www.oref.org.il/assets/audios/WarningMessagesSounds/hostileAircraftIntrusion-{lang 3-letter code}.mp4
	 */
	public void playClip(int alertCategory, LanguageCode languageCode, Duration minProtectionTime) {
		@SuppressWarnings("resource")
		final Clip clip = soundClips.computeIfAbsent(
				alertCategory,
				(ThrowingFunction<Integer, Clip, ?>) cat -> {
					final InputStream resourceAsStream = getClass().getResourceAsStream("/sounds/" + languageCode.name().toLowerCase(Locale.ROOT) + "/" + cat + ".wav");
					if (resourceAsStream == null) {
						return defaultClip;
					}
					final Clip newClip = AudioSystem.getClip();
					newClip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(resourceAsStream)));
					return newClip;
				}
		);
		clip.setFramePosition(0);
		clip.loop(alertCategory == 10 ?
				0 : // same as `clip.start()`, will play the sound once
				Math.max(0, (int) minProtectionTime.dividedBy(ChronoUnit.MICROS.getDuration().multipliedBy(clip.getMicrosecondLength())) - 1));
	}

	public void playDefaultClip() {
		defaultClip.setFramePosition(0);
		defaultClip.start();
	}

	public void prepareForOtherLanguage() {
		soundClips.values()
				.stream()
				.filter(Predicate.not(defaultClip::equals))
				.forEach(clip -> {
					try (clip) {
					}
				});
		soundClips.values()
				.removeIf(Predicate.not(defaultClip::equals)); // TODO think about what if clips not identical between languages
	}

	@Override
	public void close() {
		try (defaultClip) {
		}
		for (Clip clip : soundClips.values()) {
			try (clip) {
			}
		}
	}

}
