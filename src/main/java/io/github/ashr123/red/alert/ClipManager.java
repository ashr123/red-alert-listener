package io.github.ashr123.red.alert;

import io.github.ashr123.exceptional.functions.ThrowingFunction;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class ClipManager implements AutoCloseable {
	private final Clip alarmClip;
	/// For `catId = 13`.
	///
	/// See <https://www.oref.org.il/assets/audios/WarningMessagesSounds/update-{lang 3-letter code}.mp3>.
	private final Clip updateClip;
	/// For `catId = 14`.
	///
	/// See <https://www.oref.org.il/assets/audios/WarningMessagesSounds/flash-{lang 3-letter code}.mp3>.
	private final Clip flashClip;
	private final Map<Integer, Clip> soundClips = new ConcurrentHashMap<>(13); // ref.alertsTranslations.size() on 9/10/2025

	public ClipManager() throws LineUnavailableException, UnsupportedAudioFileException, IOException {
		// TODO to be used with Lazy Constants when this feature comes out of preview
		alarmClip = loadClip("/sounds/alarm.wav");
		updateClip = loadClip("/sounds/update.wav");
		flashClip = loadClip("/sounds/flash.wav");
	}

	private static Clip loadClip(InputStream resourceAsStream) throws LineUnavailableException, UnsupportedAudioFileException, IOException {
		final Clip clip = AudioSystem.getClip(/*Stream.of(AudioSystem.getMixerInfo()).parallel().unordered()
				.filter(mixerInfo -> COLLATOR.equals(mixerInfo.getName(), "default [default]"))
				.findAny()
				.orElse(null)*/);
		try (resourceAsStream;
		     BufferedInputStream bufferedInputStream = new BufferedInputStream(resourceAsStream);
		     AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(bufferedInputStream)) {
			clip.open(audioInputStream);
			return clip;
		} catch (IOException | LineUnavailableException | UnsupportedAudioFileException | RuntimeException e) {
			clip.close();
			throw e;
		}
	}

	private Clip loadClip(String resourcePath) throws LineUnavailableException, UnsupportedAudioFileException, IOException {
		return loadClip(Objects.requireNonNull(getClass().getResourceAsStream(resourcePath), resourcePath));
	}

	/// See <https://www.oref.org.il/assets/audios/WarningMessagesSounds/hostileAircraftIntrusion-{lang 3-letter code}.mp4>.
	public void playClip(int alertCategory, int catId, LanguageCode languageCode, Duration minProtectionTime) {
		if (alertCategory == 10 || alertCategory == 110) {
			final Clip clip = catId == 14 ? flashClip : updateClip;
			clip.setFramePosition(0);
			clip.start();
		} else {
			@SuppressWarnings("resource") final Clip clip = soundClips.computeIfAbsent(
					alertCategory,
					(ThrowingFunction<Integer, Clip, ?>) cat -> {
						final InputStream resourceAsStream = getClass().getResourceAsStream("/sounds/" + languageCode.name().toLowerCase(Locale.ROOT) + "/" + cat + ".wav");
						return resourceAsStream == null ?
								alarmClip :
								loadClip(resourceAsStream);
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
				.removeIf(clip -> {
					if (clip == alarmClip) {
						return false;
					}
					try (clip) {
					}
					return true;
				});
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
