package com.asjohn720.s1player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
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
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(queue.get(i).getAbsolutePath());
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    prepared = true;
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
        super.onDestroy();
    }
}
