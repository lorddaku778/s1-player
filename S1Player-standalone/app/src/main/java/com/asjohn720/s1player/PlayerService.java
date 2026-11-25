package com.asjohn720.s1player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Plays the queue with a single MediaPlayer. Keeps playing after the screen is closed. */
public class PlayerService extends Service {

    public interface Listener { void onChanged(); }

    public class LocalBinder extends Binder {
        PlayerService get() { return PlayerService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private MediaPlayer mp;
    private final List<File> queue = new ArrayList<>();
    private int index = -1;
    private boolean shuffle = false;
    private int repeat = 0; // 0 off, 1 all, 2 one
    private boolean prepared = false;
    private Listener listener;
    private final Random rnd = new Random();

    // ---- sound effects. fx[0] position 0..200 (100 = centre), fx[1..4] 0..100
    // 1 = surround / 3D, 2 = virtual subwoofer, 3 = bass level, 4 = loudness
    private final int[] fx = new int[]{100, 0, 0, 0, 0};
    private int sessionId;
    private Virtualizer virt;
    private Equalizer eq;
    private BassBoost bass;
    private LoudnessEnhancer loud;

    @Override public void onCreate() {
        super.onCreate();
        initEffects();
    }

    private void initEffects() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            sessionId = am.generateAudioSessionId();
        } catch (Exception e) { sessionId = 0; }
        try { virt = new Virtualizer(0, sessionId); } catch (Throwable ignored) { virt = null; }
        try { eq = new Equalizer(0, sessionId); } catch (Throwable ignored) { eq = null; }
        try { bass = new BassBoost(0, sessionId); } catch (Throwable ignored) { bass = null; }
        try { loud = new LoudnessEnhancer(sessionId); } catch (Throwable ignored) { loud = null; }
        SharedPreferences sp = getSharedPreferences("fx", MODE_PRIVATE);
        for (int i = 0; i < fx.length; i++) {
            fx[i] = sp.getInt("fx" + i, fx[i]);
            applyFx(i);
        }
    }

    int fxGet(int i) { return fx[i]; }

    boolean fxSupported(int i) {
        switch (i) {
            case 1: return virt != null;
            case 2: return eq != null;
            case 3: return bass != null;
            case 4: return loud != null;
            default: return true;
        }
    }

    void fxSet(int i, int v) {
        fx[i] = v;
        getSharedPreferences("fx", MODE_PRIVATE).edit().putInt("fx" + i, v).apply();
        applyFx(i);
    }

    private void applyVolume() {
        if (mp == null || !prepared) return;
        int b = fx[0] - 100;
        float l = b > 0 ? 1f - b / 100f : 1f;
        float r = b < 0 ? 1f + b / 100f : 1f;
        try { mp.setVolume(l, r); } catch (Exception ignored) { }
    }

    private void applyFx(int i) {
        try {
            switch (i) {
                case 0:
                    applyVolume();
                    break;
                case 1:
                    if (virt != null) {
                        if (virt.getStrengthSupported()) virt.setStrength((short) (fx[1] * 10));
                        virt.setEnabled(fx[1] > 0);
                    }
                    break;
                case 2:
                    if (eq != null) {
                        short[] range = eq.getBandLevelRange();
                        int bands = eq.getNumberOfBands();
                        for (int b = 0; b < Math.min(2, bands); b++) {
                            float part = b == 0 ? 1f : 0.5f;
                            eq.setBandLevel((short) b, (short) Math.round(range[1] * part * fx[2] / 100f));
                        }
                        eq.setEnabled(fx[2] > 0);
                    }
                    break;
                case 3:
                    if (bass != null) {
                        if (bass.getStrengthSupported()) bass.setStrength((short) (fx[3] * 10));
                        bass.setEnabled(fx[3] > 0);
                    }
                    break;
                case 4:
                    if (loud != null) {
                        loud.setTargetGain(fx[4] * 10); // millibels, up to +10 dB
                        loud.setEnabled(fx[4] > 0);
                    }
                    break;
            }
        } catch (Exception ignored) { }
    }

    @Override public IBinder onBind(Intent i) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground("S1 Player");
        return START_STICKY;
    }

    private void startAsForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("play", "Playback", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "play")
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle("S1 Player")
                .setContentText(text)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    void setListener(Listener l) { listener = l; }

    private void changed() { if (listener != null) listener.onChanged(); }

    void setQueue(List<File> files) {
        queue.clear();
        queue.addAll(files);
    }

    List<File> queue() { return queue; }
    int index() { return index; }
    boolean isPlaying() { return mp != null && prepared && mp.isPlaying(); }
    boolean isShuffle() { return shuffle; }
    void toggleShuffle() { shuffle = !shuffle; changed(); }
    int repeat() { return repeat; }
    void toggleRepeat() { repeat = (repeat + 1) % 3; changed(); }

    String title() {
        if (index < 0 || index >= queue.size()) return "Nothing playing";
        return queue.get(index).getName();
    }

    int position() { return (mp != null && prepared) ? mp.getCurrentPosition() : 0; }
    int duration() { return (mp != null && prepared) ? mp.getDuration() : 0; }
    void seekTo(int ms) { if (mp != null && prepared) mp.seekTo(ms); }

    void play(int i) {
        if (queue.isEmpty()) return;
        if (i < 0) i = queue.size() - 1;
        if (i >= queue.size()) i = 0;
        index = i;
        release();
        prepared = false;
        try {
            mp = new MediaPlayer();
            try { mp.setAudioSessionId(sessionId); } catch (Exception ignored) { }
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(queue.get(i).getAbsolutePath());
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    applyVolume();
                    m.start();
                    changed();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    if (repeat == 2) { play(index); return; }
                    if (!shuffle && repeat == 0 && index >= queue.size() - 1) { changed(); return; }
                    next();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    next();
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            next();
        }
        changed();
    }

    void toggle() {
        if (mp == null || !prepared) {
            if (!queue.isEmpty()) play(index < 0 ? 0 : index);
            return;
        }
        if (mp.isPlaying()) mp.pause(); else mp.start();
        changed();
    }

    void next() {
        if (queue.isEmpty()) return;
        play(shuffle ? rnd.nextInt(queue.size()) : index + 1);
    }

    void prev() {
        if (queue.isEmpty()) return;
        if (mp != null && prepared && mp.getCurrentPosition() > 4000) { mp.seekTo(0); return; }
        play(index - 1);
    }

    private void release() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
    }

    @Override public void onDestroy() {
        release();
        try { if (virt != null) virt.release(); } catch (Exception ignored) { }
        try { if (eq != null) eq.release(); } catch (Exception ignored) { }
        try { if (bass != null) bass.release(); } catch (Exception ignored) { }
        try { if (loud != null) loud.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
