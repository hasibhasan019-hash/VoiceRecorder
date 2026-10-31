package com.hb.voicerecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecorderService extends Service {

    static final String ACT_START = "com.hb.voicerecorder.START";
    static final String ACT_PAUSE = "com.hb.voicerecorder.PAUSE";
    static final String ACT_RESUME = "com.hb.voicerecorder.RESUME";
    static final String ACT_STOP = "com.hb.voicerecorder.STOP";

    // 0 = idle, 1 = recording, 2 = paused
    static volatile int state = 0;
    static volatile long accum = 0;
    static volatile long segStart = 0;
    static volatile int saved = 0;
    static volatile String error = null;

    private static final String CHANNEL = "rec";
    private static final int NID = 1;

    private MediaRecorder rec;
    private File tmp;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_START.equals(a)) {
            start(startId);
        } else if (ACT_PAUSE.equals(a)) {
            pause();
        } else if (ACT_RESUME.equals(a)) {
            resume();
        } else if (ACT_STOP.equals(a)) {
            stopAndSave(startId);
        } else if (state == 0) {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private Notification notif(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Voice Recorder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void start(int startId) {
        startForeground(NID, notif("Recording cholche..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        if (state != 0) return;

        MediaRecorder r = null;
        try {
            tmp = new File(getCacheDir(), "rec_" + System.currentTimeMillis() + ".m4a");
            r = new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(96000);
            r.setOutputFile(tmp.getAbsolutePath());
            r.prepare();
            r.start();
            rec = r;
            accum = 0;
            segStart = SystemClock.elapsedRealtime();
            error = null;
            state = 1;
        } catch (Exception e) {
            if (r != null) {
                try { r.release(); } catch (Exception ignored) { }
            }
            rec = null;
            error = "Recording shuru kora gelo na. Onno app mic use korche kina dekho.";
            stopForeground(true);
            stopSelf(startId);
        }
    }

    private void pause() {
        if (state != 1 || rec == null) return;
        try {
            rec.pause();
            accum += SystemClock.elapsedRealtime() - segStart;
            state = 2;
            getSystemService(NotificationManager.class)
                    .notify(NID, notif("Pause kora ache"));
        } catch (Exception ignored) { }
    }

    private void resume() {
        if (state != 2 || rec == null) return;
        try {
            rec.resume();
            segStart = SystemClock.elapsedRealtime();
            state = 1;
            getSystemService(NotificationManager.class)
                    .notify(NID, notif("Recording cholche..."));
        } catch (Exception ignored) { }
    }

    private void stopAndSave(final int startId) {
        if (state == 0 || rec == null) {
            stopForeground(true);
            stopSelf(startId);
            return;
        }
        boolean ok = true;
        try {
            rec.stop();
        } catch (RuntimeException e) {
            ok = false; // onek chhoto recording
        }
        try { rec.release(); } catch (Exception ignored) { }
        rec = null;
        state = 0;
        accum = 0;
        stopForeground(true);

        final File f = tmp;
        final boolean good = ok;
        new Thread(() -> {
            if (good) save(f);
            else error = "Recording onek chhoto, save hoy ni.";
            f.delete();
            saved++;
            stopSelf(startId);
        }).start();
    }

    private void save(File f) {
        Uri u = null;
        ContentResolver cr = getContentResolver();
        try {
            String name = "Rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name + ".m4a");
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/VoiceRecorder");
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            u = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("insert fail");

            try (InputStream in = new FileInputStream(f);
                 OutputStream out = cr.openOutputStream(u)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(u, v, null, null);
        } catch (Exception e) {
            if (u != null) {
                try { cr.delete(u, null, null); } catch (Exception ignored) { }
            }
            error = "Save korte somoshya hoyeche. Phone e jaygа ache kina dekho.";
        }
    }

    @Override
    public void onDestroy() {
        if (rec != null) {
            try { rec.release(); } catch (Exception ignored) { }
            rec = null;
        }
        super.onDestroy();
    }
}
