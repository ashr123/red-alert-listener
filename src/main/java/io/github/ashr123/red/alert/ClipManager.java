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
	private final Clip alarmClip = AudioSystem.getClip(/*Stream.of(AudioSystem.getMixerInfo()).parallel().unordered()
				.filter(mixerInfo -> COLLATOR.equals(mixerInfo.getName(), "default [default]"))
				.findAny()
				.orElse(null)*/);
	/**
	 * For catId = 13
	 * <p>
	 * See https://www.oref.org.il/assets/audios/WarningMessagesSounds/update-{lang 3-letter code}.mp3
	 */
	private final Clip updateClip = AudioSystem.getClip(/*Stream.of(AudioSystem.getMixerInfo()).parallel().unordered()
				.filter(mixerInfo -> COLLATOR.equals(mixerInfo.getName(), "default [default]"))
				.findAny()
				.orElse(null)*/);
	/**
	 * For catId = 14
	 * <p>
	 * See https://www.oref.org.il/assets/audios/WarningMessagesSounds/flash-{lang 3-letter code}.mp3
	 */
	// catId = 14
	private final Clip flashClip = AudioSystem.getClip(/*Stream.of(AudioSystem.getMixerInfo()).parallel().unordered()
				.filter(mixerInfo -> COLLATOR.equals(mixerInfo.getName(), "default [default]"))
				.findAny()
				.orElse(null)*/);
	private final Map<Integer, Clip> soundClips = new ConcurrentHashMap<>(13); // ref.alertsTranslations.size() on 9/10/2025

	public ClipManager() throws LineUnavailableException, UnsupportedAudioFileException, IOException {
		// TODO to be used with Lazy Constants when this feature comes out of preview
		alarmClip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/sounds/alarm.wav")))));
		updateClip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/sounds/update.wav")))));
		flashClip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/sounds/flash.wav")))));
	}

	/**
	 *  See https://www.oref.org.il/assets/audios/WarningMessagesSounds/hostileAircraftIntrusion-{lang 3-letter code}.mp4
	 */
	public void playClip(int alertCategory, int catId, LanguageCode languageCode, Duration minProtectionTime) {
		if (alertCategory == 10 || alertCategory == 110) {
			final Clip clip = catId == 14 ? flashClip : updateClip;
			clip.setFramePosition(0);
			clip.start();
		}
		else {
			@SuppressWarnings("resource")
			final Clip clip = soundClips.computeIfAbsent(
							alertCategory,
							(ThrowingFunction<Integer, Clip, ?>) cat -> {
								final InputStream resourceAsStream = getClass().getResourceAsStream("/sounds/" + languageCode.name().toLowerCase(Locale.ROOT) + "/" + cat + ".wav");
								if (resourceAsStream == null) {
									return alarmClip;
								}
								final Clip newClip = AudioSystem.getClip();
								newClip.open(AudioSystem.getAudioInputStream(new BufferedInputStream(resourceAsStream)));
								return newClip;
							}
					);
			clip.setFramePosition(0);
			clip.loop(Math.max(0, (int) minProtectionTime.dividedBy(ChronoUnit.MICROS.getDuration().multipliedBy(clip.getMicrosecondLength())) - 1));
		}
	}

	public void playAlarmClip() {
		alarmClip.setFramePosition(0);
		alarmClip.start();
	}

	public void prepareForOtherLanguage() {
		soundClips.values()
				.stream()
				.filter(Predicate.not(clip -> alarmClip.equals(clip) || updateClip.equals(clip) || flashClip.equals(clip)))
				.forEach(clip -> {
					try (clip) {
					}
				});
		soundClips.values()
				.removeIf(Predicate.not(clip -> alarmClip.equals(clip) || updateClip.equals(clip) || flashClip.equals(clip))); // TODO think about what if clips not identical between languages
	}

	@Override
	public void close() {
		try (alarmClip;
			 updateClip;
			 flashClip) {
		}
		for (Clip clip : soundClips.values()) {
			try (clip) {
			}
		}
	}

}
