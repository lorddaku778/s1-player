package com.asjohn720.s1player;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline player for the songs Music Sender copies into /sdcard/Music/S1Songs.
 * Laid out to fit the top half of the scooter screen (the half that still has touch):
 * album art | now playing + controls | queue.
 */
public class PlayerActivity extends Activity implements PlayerService.Listener {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFB3B3B3;
    private static final int DIM = 0xFF6E6E6E;

    private PlayerService svc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<File> files = new ArrayList<>();
    private final Map<String, String[]> meta = new ConcurrentHashMap<>(); // path -> {title, artist}

    private ImageView art;
    private TextView title, artist, tPos, tDur, count;
    private SeekBar seek;
    private ImageButton btnPlay, btnShuffle, btnRepeat;
    private GradientDrawable playBg;
    private LinearLayout root;
    private ListView list;
    private TextView tabQueue, tabSound;
    private android.widget.ScrollView soundScroll;
    private final SeekBar[] fxBars = new SeekBar[5];
    private final TextView[] fxVals = new TextView[5];
    private boolean syncing;
    private QueueAdapter adapter;
    private boolean touching;
    private String artFor = "";
    private int dominant = 0xFF111111;
    private int h; // height of the working half

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).get();
            svc.setListener(PlayerActivity.this);
            if (svc.queue().isEmpty()) svc.setQueue(files);
            syncFx();
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (svc != null && !touching) {
                int d = svc.duration();
                seek.setMax(Math.max(d, 1));
                seek.setProgress(svc.position());
                tPos.setText(fmt(svc.position()));
                tDur.setText(fmt(d));
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        } else {
            load();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) { load(); }

    // ---------------------------------------------------------------- data

    private void load() {
        files = scan();
        count.setText(files.isEmpty() ? "No songs in Music/S1Songs" : files.size() + " songs");
        if (files.isEmpty()) title.setText("Send songs with Music Sender");
        adapter = new QueueAdapter();
        list.setAdapter(adapter);
        Intent i = new Intent(this, PlayerService.class);
        startForegroundService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
        ui.post(tick);
        readMetaInBackground(new ArrayList<>(files));
    }

    private void readMetaInBackground(final List<File> src) {
        new Thread(new Runnable() {
            @Override public void run() {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                int n = 0;
                for (File f : src) {
                    try {
                        r.setDataSource(f.getAbsolutePath());
                        String t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                        String a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                        meta.put(f.getAbsolutePath(), new String[]{
                                t == null || t.trim().isEmpty() ? null : t.trim(),
                                a == null || a.trim().isEmpty() ? null : a.trim()});
                    } catch (Exception ignored) { }
                    if (++n % 15 == 0) ui.post(new Runnable() {
                        @Override public void run() { if (adapter != null) adapter.notifyDataSetChanged(); refresh(); }
                    });
                }
                try { r.release(); } catch (Exception ignored) { }
                ui.post(new Runnable() {
                    @Override public void run() { if (adapter != null) adapter.notifyDataSetChanged(); refresh(); }
                });
            }
        }).start();
    }

    private String titleOf(File f) {
        String[] m = meta.get(f.getAbsolutePath());
        if (m != null && m[0] != null) return m[0];
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private String artistOf(File f) {
        String[] m = meta.get(f.getAbsolutePath());
        return (m != null && m[1] != null) ? m[1] : "Unknown artist";
    }

    private List<File> scan() {
        List<File> out = new ArrayList<>();
        walk(new File(Environment.getExternalStorageDirectory(), "Music/S1Songs"), out);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return natural(a.getAbsolutePath(), b.getAbsolutePath());
            }
        });
        return out;
    }

    /** Sorts 1, 2, 3 ... 10, 11 in number order instead of 1, 10, 11, 2. Leading zeros are ignored. */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+", "");
                String nb = b.substring(sj, j).replaceFirst("^0+", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private void walk(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) { walk(f, out); continue; }
            String n = f.getName().toLowerCase();
            if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".flac")
                    || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".opus")) out.add(f);
        }
    }

    private static String fmt(int ms) {
        int s = Math.max(ms, 0) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private ImageButton iconBtn(int res, int sizeDp, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackground(null);
        b.setColorFilter(WHITE);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(sizeDp / 5f);
        b.setPadding(pad, pad, pad, pad);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private void buildUi() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        h = m.heightPixels / 2;               // only the top half of the scooter screen takes touch
        int pad = dp(10);

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(0xFF000000);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{dominant, 0xFF000000}));
        screen.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        // --- album art
        int artSize = h - pad * 2;
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(rounded(0xFF1E1E1E, dp(12)));
        art.setClipToOutline(true);
        art.setImageResource(R.drawable.ic_note);
        art.setColorFilter(DIM);
        art.setPadding(artSize / 4, artSize / 4, artSize / 4, artSize / 4);
        root.addView(art, new LinearLayout.LayoutParams(artSize, artSize));

        // --- middle column
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.setPadding(dp(14), 0, dp(14), 0);
        root.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));

        title = text("S1 Player", 18, WHITE, true);
        artist = text("", 13, GREY, false);
        count = text("", 11, DIM, false);
        mid.addView(title);
        mid.addView(artist);
        mid.addView(count);

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        tPos = text("0:00", 11, GREY, false);
        tDur = text("0:00", 11, GREY, false);
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(WHITE));
        seek.setThumbTintList(ColorStateList.valueOf(WHITE));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) tPos.setText(fmt(p)); }
            @Override public void onStartTrackingTouch(SeekBar s) { touching = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                touching = false;
                if (svc != null) svc.seekTo(s.getProgress());
            }
        });
        seekRow.addView(tPos);
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seekRow.addView(tDur);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = dp(8);
        mid.addView(seekRow, srp);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        btnShuffle = iconBtn(R.drawable.ic_shuffle, 40, new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.toggleShuffle(); }
        });
        ImageButton prev = iconBtn(R.drawable.ic_prev, 46, new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.prev(); }
        });
        btnPlay = iconBtn(R.drawable.ic_play, 58, new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.toggle(); }
        });
        playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(WHITE);
        btnPlay.setBackground(playBg);
        btnPlay.setColorFilter(0xFF000000);
        ImageButton next = iconBtn(R.drawable.ic_next, 46, new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.next(); }
        });
        btnRepeat = iconBtn(R.drawable.ic_repeat, 40, new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.toggleRepeat(); }
        });
        for (View v : new View[]{btnShuffle, prev, btnPlay, next, btnRepeat}) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = lp.rightMargin = dp(5);
            ctl.addView(v);
        }
        mid.addView(ctl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- queue
        LinearLayout q = new LinearLayout(this);
        q.setOrientation(LinearLayout.VERTICAL);
        q.setBackground(rounded(0x66000000, dp(12)));
        q.setPadding(dp(4), dp(6), dp(4), dp(4));
        LinearLayout tabs = new LinearLayout(this);
        tabQueue = text("Up next", 12, WHITE, true);
        tabSound = text("Sound", 12, DIM, true);
        tabQueue.setPadding(dp(8), dp(2), dp(16), dp(6));
        tabSound.setPadding(0, dp(2), dp(8), dp(6));
        tabQueue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(false); }
        });
        tabSound.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(true); }
        });
        tabs.addView(tabQueue);
        tabs.addView(tabSound);
        q.addView(tabs);
        list = new ListView(this);
        list.setDivider(null);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                if (svc != null) { svc.setQueue(files); svc.play(pos); }
            }
        });
        FrameLayout body = new FrameLayout(this);
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        soundScroll = buildSoundPanel();
        soundScroll.setVisibility(View.GONE);
        body.addView(soundScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        q.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f));

        setContentView(screen);
    }

    private void showTab(boolean sound) {
        list.setVisibility(sound ? View.GONE : View.VISIBLE);
        soundScroll.setVisibility(sound ? View.VISIBLE : View.GONE);
        tabQueue.setTextColor(sound ? DIM : WHITE);
        tabSound.setTextColor(sound ? WHITE : DIM);
        if (sound) syncFx();
    }

    private static final String[] FX_NAMES = {
            "Sound field / position", "Surround / 3D sound", "Virtual subwoofer", "Bass level", "Loudness"};

    private android.widget.ScrollView buildSoundPanel() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), 0, dp(8), dp(6));

        LinearLayout chips = new LinearLayout(this);
        chips.addView(chip("Flat", new int[]{100, 0, 0, 0, 0}));
        chips.addView(chip("Cinema", new int[]{100, 70, 40, 40, 30}));
        chips.addView(chip("Bass", new int[]{100, 20, 70, 80, 20}));
        col.addView(chips);

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout head = new LinearLayout(this);
            TextView name = text(FX_NAMES[i], 12, WHITE, false);
            fxVals[i] = text("", 11, GREY, false);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(fxVals[i]);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.topMargin = dp(6);
            col.addView(head, hp);

            SeekBar sb = new SeekBar(this);
            sb.setMax(i == 0 ? 200 : 100);
            sb.setProgressTintList(ColorStateList.valueOf(WHITE));
            sb.setThumbTintList(ColorStateList.valueOf(WHITE));
            sb.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    fxLabel(idx, p);
                    if (user && !syncing && svc != null) svc.fxSet(idx, p);
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            fxBars[i] = sb;
            col.addView(sb);
        }
        sv.addView(col);
        return sv;
    }

    private TextView chip(String label, final int[] values) {
        TextView t = text(label, 12, WHITE, true);
        t.setBackground(rounded(0x33FFFFFF, dp(8)));
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (svc == null) return;
                for (int i = 0; i < values.length; i++) svc.fxSet(i, values[i]);
                syncFx();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void fxLabel(int i, int p) {
        if (i == 0) {
            int b = p - 100;
            fxVals[i].setText(b == 0 ? "Centre" : (b < 0 ? "Left " : "Right ") + Math.abs(b) + "%");
        } else {
            fxVals[i].setText(p == 0 ? "Off" : p + "%");
        }
    }

    private void syncFx() {
        if (svc == null) return;
        syncing = true;
        for (int i = 0; i < 5; i++) {
            boolean ok = svc.fxSupported(i);
            fxBars[i].setEnabled(ok);
            fxBars[i].setProgress(svc.fxGet(i));
            if (ok) fxLabel(i, svc.fxGet(i)); else fxVals[i].setText("not supported");
        }
        syncing = false;
    }

    private TextView text(String t, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private class QueueAdapter extends BaseAdapter {
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int i) { return files.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View v, ViewGroup parent) {
            LinearLayout row;
            if (v == null) {
                row = new LinearLayout(PlayerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(8), dp(5), dp(8), dp(5));
                row.addView(text("", 13, WHITE, false));
                row.addView(text("", 11, DIM, false));
            } else {
                row = (LinearLayout) v;
            }
            File f = files.get(pos);
            boolean current = svc != null && svc.index() == pos;
            TextView a = (TextView) row.getChildAt(0);
            TextView b = (TextView) row.getChildAt(1);
            a.setText(titleOf(f));
            a.setTextColor(current ? WHITE : 0xFFD0D0D0);
            a.setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            b.setText(artistOf(f));
            row.setBackground(current ? rounded(0x33FFFFFF, dp(8)) : null);
            return row;
        }
    }

    // ---------------------------------------------------------------- state

    @Override
    public void onChanged() {
        ui.post(new Runnable() { @Override public void run() { refresh(); } });
    }

    private void refresh() {
        if (svc == null) return;
        int idx = svc.index();
        if (idx >= 0 && idx < files.size()) {
            File f = files.get(idx);
            title.setText(titleOf(f));
            artist.setText(artistOf(f));
            if (!f.getAbsolutePath().equals(artFor)) {
                artFor = f.getAbsolutePath();
                loadArt(f);
            }
        }
        btnPlay.setImageResource(svc.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        btnShuffle.setColorFilter(svc.isShuffle() ? 0xFF3FB950 : GREY);
        int rep = svc.repeat();
        btnRepeat.setImageResource(rep == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        btnRepeat.setColorFilter(rep == 0 ? GREY : 0xFF3FB950);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadArt(final File f) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(f.getAbsolutePath());
                    byte[] pic = r.getEmbeddedPicture();
                    r.release();
                    if (pic != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                        int s = 1;
                        while (o.outWidth / (s * 2) >= 400) s *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = s;
                        bmp = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                    }
                } catch (Exception ignored) { }
                final Bitmap result = bmp;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!f.getAbsolutePath().equals(artFor)) return;
                        if (result != null) {
                            art.setPadding(0, 0, 0, 0);
                            art.clearColorFilter();
                            art.setImageBitmap(result);
                            Bitmap one = Bitmap.createScaledBitmap(result, 1, 1, true);
                            int c = one.getPixel(0, 0);
                            float[] hsv = new float[3];
                            Color.colorToHSV(c, hsv);
                            hsv[2] = Math.min(hsv[2], 0.35f);
                            dominant = Color.HSVToColor(hsv);
                        } else {
                            int p = art.getWidth() / 4;
                            art.setPadding(p, p, p, p);
                            art.setImageResource(R.drawable.ic_note);
                            art.setColorFilter(DIM);
                            dominant = 0xFF161616;
                        }
                        root.setBackground(new GradientDrawable(
                                GradientDrawable.Orientation.TL_BR, new int[]{dominant, 0xFF000000}));
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        if (svc != null) {
            svc.setListener(null);
            try { unbindService(conn); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
