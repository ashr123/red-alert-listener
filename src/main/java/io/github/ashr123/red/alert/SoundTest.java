package io.github.ashr123.red.alert;

import com.sun.javafx.application.PlatformImpl;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Scanner;

public class SoundTest
{
	public static void main(String[] args)
	{
		testJavaFX();
	}

	private static void testJavaFX()
	{
		// String audioFilePath = "AudioFileWithWavFormat.wav";
		// String audioFilePath = "AudioFileWithMpegFormat.mpeg";

		try
		{
			PlatformImpl.startup(() ->
			{
			});

			final MediaPlayer mp3Player = new MediaPlayer(new Media(Objects.requireNonNull(SoundTest.class.getResource("/alarmSound.wav")).toExternalForm()));
			mp3Player.setOnPlaying(() -> System.out.println("Playback started"));
//			mp3Player.setOnStopped(PlatformImpl::exit);
			mp3Player.setOnEndOfMedia(PlatformImpl::exit);

			mp3Player.play();

		} catch (Exception ex)
		{
			System.out.println("Error occurred during playback process:" + ex.getMessage());
		}
	}

	private static void testMixers() throws IOException, UnsupportedAudioFileException
	{
		try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(SoundTest.class.getResourceAsStream("/alarmSound.wav")))))
		{
			final Scanner scanner = new Scanner(System.in);
			for (Mixer.Info info : AudioSystem.getMixerInfo())
			{
				System.out.println("Testing sound for mixer \"" + info + "\"...");
				try (Clip clip = AudioSystem.getClip(info))
				{
					clip.open(audioInputStream);
					clip.setFramePosition(0);
					clip.start();
					System.out.println("Did you hear any sound? (true|false)");
					if (Boolean.parseBoolean(scanner.nextLine()))
						return;
				} catch (Exception e)
				{
					System.out.println(e);
				}
			}
		}
		System.out.println("Mixer not found!");
	}
}
