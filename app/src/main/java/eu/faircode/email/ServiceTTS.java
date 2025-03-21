package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2025 by Marcel Bokhorst (M66B)
*/

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.OperationCanceledException;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ServiceTTS extends ServiceBase {
    private Integer status = null;
    private TextToSpeech instance = null;
    private final List<Runnable> queue = new ArrayList<>();
    private final Object lock = new Object();

    static final String EXTRA_FLUSH = "flush";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_LANGUAGE = "language";
    static final String EXTRA_UTTERANCE_ID = "utterance";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_GROUP = "group";

    static final String ACTION_TTS_COMPLETED = BuildConfig.APPLICATION_ID + ".TTS";

    static final int PI_TTS = 1;
    static final int PI_FLUSH = 2;

    @Override
    public void onCreate() {
        Log.i("Service TTS create");
        super.onCreate();
        try {
            startForeground(NotificationHelper.NOTIFICATION_TTS, getNotification("tts:0"));
        } catch (Throwable ex) {
            if (Helper.isPlayStoreInstall())
                Log.i(ex);
            else
                Log.e(ex);
        }
    }

    @Override
    public void onTimeout(int startId) {
        String msg = "onTimeout" +
                " class=" + this.getClass().getName() +
                " ignoring=" + Helper.isIgnoringOptimizations(this);
        Log.e(new Throwable(msg));
        EntityLog.log(this, EntityLog.Type.Debug3, msg);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.i("Service TTS destroy");
        stopForeground(true);
        super.onDestroy();
        CoalMine.watch(this, this.getClass().getName() + "#onDestroy");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        EntityLog.log(this, "Service TTS intent=" + intent);
        Log.logExtras(intent);

        super.onStartCommand(intent, flags, startId);

        if (intent == null)
            return START_NOT_STICKY;

        onTts(intent);

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification getNotification(String utteranceId) {
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, "progress")
                        .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFAULT)
                        .setSmallIcon(R.drawable.twotone_stop_24)
                        .setContentTitle(getString(R.string.title_rule_tts))
                        .setContentIntent(getPendingIntent(this, utteranceId))
                        .setAutoCancel(false)
                        .setShowWhen(false)
                        .setDefaults(0) // disable sound on pre Android 8
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setCategory(NotificationCompat.CATEGORY_SERVICE)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                        .setLocalOnly(true)
                        .setOngoing(true);

        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_NO_CLEAR;
        return notification;
    }

    private static PendingIntent getPendingIntent(Context context, String utteranceId) {
        Intent flush = new Intent(context, ServiceTTS.class)
                .setAction("tts:" + 0)
                .putExtra(ServiceTTS.EXTRA_FLUSH, true)
                .putExtra(ServiceTTS.EXTRA_TEXT, "")
                .putExtra(ServiceTTS.EXTRA_LANGUAGE, (String) null)
                .putExtra(ServiceTTS.EXTRA_UTTERANCE_ID, utteranceId);
        return PendingIntentCompat.getService(
                context, PI_FLUSH, flush, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    void onTts(Intent intent) {
        final boolean flush = intent.getBooleanExtra(EXTRA_FLUSH, false);
        final String text = intent.getStringExtra(EXTRA_TEXT);
        final String language = intent.getStringExtra(EXTRA_LANGUAGE);
        final String utteranceId = intent.getStringExtra(EXTRA_UTTERANCE_ID);
        final long group = intent.getLongExtra(EXTRA_GROUP, 0);
        final long message = intent.getLongExtra(EXTRA_MESSAGE, -1L);

        final Locale locale = (language == null ? Locale.getDefault() : new Locale(language));

        if (message > 0) {
            String tag = "unseen." + group + "." + message;
            NotificationManager nm = Helper.getSystemService(this, NotificationManager.class);
            nm.cancel(tag, NotificationHelper.NOTIFICATION_TAGGED);

            Helper.getSerialExecutor().submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        String body = Helper.readText(EntityMessage.getFile(ServiceTTS.this, message));
                        String text = HtmlHelper.getFullText(ServiceTTS.this, body);

                        // Avoid: Not enough namespace quota ... for ...
                        text = HtmlHelper.truncate(text, getMaxTextSize() / 3);

                        intent.putExtra(EXTRA_TEXT, text);
                        intent.removeExtra(EXTRA_GROUP);
                        intent.removeExtra(EXTRA_MESSAGE);
                        onTts(intent);
                    } catch (Throwable ex) {
                        Log.e(ex);
                    }
                }
            });

            return;
        }

        final Runnable speak = new RunnableEx("tts") {
            @Override
            public void delegate() {
                boolean available = (instance.setLanguage(locale) >= 0);
                EntityLog.log(ServiceTTS.this, "TTS queued" +
                        " language=" + locale +
                        " available=" + available +
                        " utterance=" + utteranceId +
                        " text=" + text);
                int error = instance.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, utteranceId);
                if (error != TextToSpeech.SUCCESS)
                    throw new OperationCanceledException("TTS error=" + error);
            }
        };

        final LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(ServiceTTS.this);

        synchronized (lock) {
            if (status == null) {
                queue.add(speak);
                if (instance == null)
                    try {
                        Log.i("TTS init");
                        instance = new TextToSpeech(ServiceTTS.this, new TextToSpeech.OnInitListener() {
                            @Override
                            public void onInit(int initStatus) {
                                instance.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                                    @Override
                                    public void onStart(String utteranceId) {
                                        Log.i("TTS start=" + utteranceId);
                                        try {
                                            startForeground(NotificationHelper.NOTIFICATION_TTS, getNotification(utteranceId));
                                        } catch (Throwable ex) {
                                            if (Helper.isPlayStoreInstall())
                                                Log.i(ex);
                                            else
                                                Log.e(ex);
                                        }
                                    }

                                    @Override
                                    public void onDone(String utteranceId) {
                                        Log.i("TTS done=" + utteranceId);
                                        report(utteranceId);
                                        synchronized (lock) {
                                            if (!instance.isSpeaking())
                                                try {
                                                    Log.i("TTS shutdown");
                                                    instance.shutdown();
                                                } catch (Throwable ex) {
                                                    Log.e(ex);
                                                } finally {
                                                    status = null;
                                                    instance = null;
                                                    ServiceTTS.this.stopSelf();
                                                }
                                        }
                                    }

                                    @Override
                                    public void onError(String utteranceId) {
                                        Log.i("TTS error=" + utteranceId);
                                        report(utteranceId);
                                    }

                                    private void report(String utteranceId) {
                                        lbm.sendBroadcast(new Intent(ACTION_TTS_COMPLETED)
                                                .putExtra(EXTRA_UTTERANCE_ID, utteranceId));
                                    }
                                });

                                synchronized (lock) {
                                    status = initStatus;
                                    Log.i("TTS status=" + status + " queued=" + queue.size());
                                    if (status == TextToSpeech.SUCCESS)
                                        for (Runnable speak : queue)
                                            speak.run();
                                    queue.clear();
                                }
                            }
                        });
                    } catch (Throwable ex) {
                        Log.e(ex);
                        status = TextToSpeech.ERROR;
                    }
            } else if (status == TextToSpeech.SUCCESS)
                speak.run();
        }
    }

    static int getMaxTextSize() {
        return TextToSpeech.getMaxSpeechInputLength();
    }
}
