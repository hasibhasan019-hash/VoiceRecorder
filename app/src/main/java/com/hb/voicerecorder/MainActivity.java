package com.hb.voicerecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;

    private static final class Rec {
        long id;
        String name;
        long duration, size;
        Uri uri() {
            return ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    private TextView timer, status;
    private Button btnRec, btnPause, btnStop;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final List<Rec> recs = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private long playingId = -1;
    private String playingName = "";
    private int seenSaved = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            h.postDelayed(this, 500);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(32), dp(16), dp(8));

        TextView title = new TextView(this);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setText("Voice Recorder");

        timer = new TextView(this);
        timer.setTextSize(48);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(0, dp(16), 0, dp(4));
        timer.setText("00:00");

        status = new TextView(this);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        btnRec = new Button(this);
        btnRec.setText("Record");
        btnRec.setOnClickListener(v -> onRecClick());

        btnPause = new Button(this);
        btnPause.setText("Pause");
        btnPause.setOnClickListener(v -> {
            sendCmd(RecorderService.state == 2
                    ? RecorderService.ACT_RESUME : RecorderService.ACT_PAUSE);
        });

        btnStop = new Button(this);
        btnStop.setText("Stop & Save");
        btnStop.setOnClickListener(v -> sendCmd(RecorderService.ACT_STOP));

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(btnRec, lp);
        row.addView(btnPause, lp);
        row.addView(btnStop, lp);

        TextView label = new TextView(this);
        label.setTextSize(16);
        label.setPadding(0, dp(16), 0, dp(4));
        label.setText("Recordings (tap = play, lomba chhapo = delete)");

        list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<String>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> togglePlay(recs.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(recs.get(pos));
            return true;
        });

        root.addView(title);
        root.addView(timer);
        root.addView(status);
        root.addView(row);
        root.addView(label);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        seenSaved = -1;
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlay();
    }

    private void update() {
        int st = RecorderService.state;
        long el = RecorderService.accum
                + (st == 1 ? SystemClock.elapsedRealtime() - RecorderService.segStart : 0);
        timer.setText(fmt(el));

        btnRec.setEnabled(st == 0);
        btnPause.setEnabled(st != 0);
        btnPause.setText(st == 2 ? "Resume" : "Pause");
        btnStop.setEnabled(st != 0);

        if (st == 1) status.setText("Recording cholche...");
        else if (st == 2) status.setText("Pause kora ache");
        else if (mp != null) status.setText("Play hocche: " + playingName);
        else status.setText("Record button chapo");

        if (seenSaved != RecorderService.saved) {
            seenSaved = RecorderService.saved;
            loadList();
        }
        String err = RecorderService.error;
        if (err != null) {
            RecorderService.error = null;
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        }
    }

    private void onRecClick() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startRec();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MIC) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                startRec();
            } else {
                Toast.makeText(this, "Mic permission na dile record kora jabe na",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startRec() {
        stopPlay();
        startForegroundService(new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACT_START));
    }

    private void sendCmd(String action) {
        startService(new Intent(this, RecorderService.class).setAction(action));
    }

    private void loadList() {
        recs.clear();
        adapter.clear();
        try (Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE},
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{"Music/VoiceRecorder%"},
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    Rec r = new Rec();
                    r.id = c.getLong(0);
                    r.name = c.getString(1);
                    r.duration = c.getLong(2);
                    r.size = c.getLong(3);
                    recs.add(r);
                    adapter.add(r.name + "\n" + fmt(r.duration) + "  •  "
                            + Formatter.formatShortFileSize(this, r.size));
                }
            }
        } catch (Exception ignored) { }
        adapter.notifyDataSetChanged();
    }

    private void togglePlay(Rec r) {
        if (RecorderService.state != 0) {
            Toast.makeText(this, "Record cholar somoy play kora jabe na",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean same = mp != null && playingId == r.id;
        stopPlay();
        if (same) return;
        try {
            mp = new MediaPlayer();
            mp.setDataSource(this, r.uri());
            mp.prepare();
            mp.setOnCompletionListener(m -> stopPlay());
            mp.start();
            playingId = r.id;
            playingName = r.name;
        } catch (Exception e) {
            stopPlay();
            Toast.makeText(this, "Play kora gelo na", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlay() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        playingId = -1;
    }

    private void confirmDelete(final Rec r) {
        new AlertDialog.Builder(this)
                .setTitle("Delete korbe?")
                .setMessage(r.name)
                .setNegativeButton("Na", null)
                .setPositiveButton("Delete", (d, w) -> {
                    if (playingId == r.id) stopPlay();
                    try {
                        getContentResolver().delete(r.uri(), null, null);
                    } catch (Exception e) {
                        Toast.makeText(this, "Delete kora gelo na",
                                Toast.LENGTH_SHORT).show();
                    }
                    loadList();
                })
                .show();
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss)
                : String.format(Locale.US, "%02d:%02d", mm, ss);
    }
}
