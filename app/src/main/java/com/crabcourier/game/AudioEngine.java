package com.crabcourier.game;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Random;

/** Small procedural audio engine: no copyrighted audio assets are required. */
public class AudioEngine {
    private static final int SAMPLE_RATE = 22050;
    private volatile boolean alive = true;
    private volatile boolean musicPlaying = false;
    private volatile int musicMode = 0;
    private final Random random = new Random();
    private final Thread musicThread;
    private AudioTrack currentMusic;

    public AudioEngine() {
        musicThread = new Thread(this::musicLoop, "crab-music");
        musicThread.start();
    }

    public void setMusicPlaying(boolean playing) {
        musicPlaying = playing;
        if (!playing && currentMusic != null) {
            try { currentMusic.pause(); } catch (Exception ignored) {}
        }
    }

    public void setMusicMode(int mode) {
        musicMode = Math.max(0, Math.min(3, mode));
        AudioTrack t = currentMusic;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
        }
    }

    public void shutdown() {
        alive = false;
        musicPlaying = false;
        AudioTrack t = currentMusic;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
        }
        musicThread.interrupt();
    }

    public void playThrow() { playNotes(new int[]{740, 990}, new int[]{55, 70}, 0.12f, false); }
    public void playServe() { playNotes(new int[]{660, 880, 1100}, new int[]{55, 55, 90}, 0.14f, false); }
    public void playBonus() { playNotes(new int[]{784, 988, 1175, 1568}, new int[]{60, 60, 60, 120}, 0.15f, false); }
    public void playLevelUp() { playNotes(new int[]{523, 659, 784, 1047}, new int[]{80, 80, 80, 150}, 0.13f, false); }
    public void playMiss() { playNotes(new int[]{330, 240, 170}, new int[]{90, 100, 170}, 0.16f, true); }
    public void playSecret() { playNotes(new int[]{523, 659, 784, 988, 1319}, new int[]{70, 70, 70, 70, 210}, 0.15f, false); }
    public void playTapGag() { playNotes(new int[]{900 + random.nextInt(250), 600 + random.nextInt(180)}, new int[]{80, 100}, 0.10f, false); }

    public void playAngry() {
        int base = 180 + random.nextInt(100);
        playNotes(new int[]{base, base + 170, base - 30, base + 110}, new int[]{70, 65, 75, 120}, 0.13f, true);
    }

    private void musicLoop() {
        while (alive) {
            if (!musicPlaying) {
                sleepQuiet(120);
                continue;
            }
            int mode = musicMode;
            short[] song = buildSong(mode);
            AudioTrack track = makeTrack(song.length, 0.045f);
            currentMusic = track;
            if (track == null) {
                sleepQuiet(500);
                continue;
            }
            try {
                track.write(song, 0, song.length, AudioTrack.WRITE_BLOCKING);
                track.setLoopPoints(0, song.length, -1);
                track.play();
                while (alive && musicPlaying && musicMode == mode) sleepQuiet(160);
            } catch (Exception ignored) {
            } finally {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
                if (currentMusic == track) currentMusic = null;
            }
        }
    }

    private short[] buildSong(int mode) {
        final int[][] notes = new int[][]{
                {659, 784, 880, 784, 659, 523, 587, 659, 784, 988, 880, 784, 659, 587, 523, 587},
                {523, 659, 784, 659, 587, 698, 880, 784, 659, 784, 1047, 880, 784, 698, 659, 587},
                {587, 740, 880, 988, 880, 740, 659, 740, 880, 1175, 988, 880, 740, 659, 587, 659},
                {392, 466, 523, 622, 523, 466, 392, 349, 392, 523, 587, 698, 622, 523, 466, 392}
        };
        final int beatMs = mode == 3 ? 210 : 175;
        int[] melody = notes[Math.max(0, Math.min(notes.length - 1, mode))];
        int samplesPerBeat = SAMPLE_RATE * beatMs / 1000;
        short[] out = new short[samplesPerBeat * melody.length];
        int cursor = 0;
        for (int note : melody) {
            writeTone(out, cursor, samplesPerBeat, note, mode == 3 ? 0.33 : 0.26, false);
            cursor += samplesPerBeat;
        }
        return out;
    }

    private void playNotes(int[] freqs, int[] durations, float volume, boolean noisy) {
        new Thread(() -> {
            int totalSamples = 0;
            for (int ms : durations) totalSamples += SAMPLE_RATE * ms / 1000;
            short[] data = new short[totalSamples];
            int cursor = 0;
            for (int i = 0; i < freqs.length; i++) {
                int count = SAMPLE_RATE * durations[Math.min(i, durations.length - 1)] / 1000;
                writeTone(data, cursor, count, freqs[i], noisy ? 0.50 : 0.38, noisy);
                cursor += count;
            }
            AudioTrack track = makeTrack(data.length, volume);
            if (track == null) return;
            try {
                track.write(data, 0, data.length, AudioTrack.WRITE_BLOCKING);
                track.play();
                int totalMs = 0;
                for (int ms : durations) totalMs += ms;
                sleepQuiet(totalMs + 40L);
            } catch (Exception ignored) {
            } finally {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
        }, "crab-sfx").start();
    }

    private AudioTrack makeTrack(int samples, float volume) {
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(Math.max(2048, samples * 2))
                    .build();
            t.setVolume(volume);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeTone(short[] dst, int start, int count, int frequency, double amp, boolean noisy) {
        double phase = 0;
        double phaseStep = 2.0 * Math.PI * frequency / SAMPLE_RATE;
        for (int i = 0; i < count && start + i < dst.length; i++) {
            double env = Math.min(1.0, i / 180.0) * Math.min(1.0, (count - i) / 260.0);
            double wave = Math.sin(phase) * 0.72 + (Math.sin(phase) >= 0 ? 0.28 : -0.28);
            if (noisy) wave += (random.nextDouble() * 2.0 - 1.0) * 0.18;
            dst[start + i] = (short) (wave * amp * env * Short.MAX_VALUE);
            phase += phaseStep;
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
