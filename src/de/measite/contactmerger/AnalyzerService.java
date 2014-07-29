package de.measite.contactmerger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import android.app.Notification;
import android.app.Notification.Builder;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import de.measite.contactmerger.graph.GraphIO;
import de.measite.contactmerger.graph.UndirectedGraph;
import de.measite.contactmerger.ui.GraphConverter;
import de.measite.contactmerger.ui.model.MergeContact;
import de.measite.contactmerger.ui.model.ModelIO;

public class AnalyzerService extends Service {

    public class AnalyzerBinder extends Binder {
        public AnalyzerService getServiceInstance() {
            return AnalyzerService.this;
        }
    }

    private static final String TAG = "ContactMerger/AnalyzerService";

    protected static AnalyzerThread analyzer;

    protected SharedPreferences scanPreferences;

    protected ArrayList<ProgressListener> listeners = new ArrayList<ProgressListener>(3);
    protected LocalBroadcastManager broadcastManager;

    protected Random rnd = new Random();

    @Override
    public void onCreate() {
        super.onCreate();
        broadcastManager = LocalBroadcastManager.getInstance(getApplicationContext());
        registerAlarm();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Binding service");
        return new AnalyzerBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        scanPreferences = getSharedPreferences("scan_preferences", 0);

        final NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (intent != null && intent.getBooleanExtra("stop", false)) {
            new Thread() {
                @Override
                public void run() {
                    // uh-uh, user-requested stop
                    while (analyzer != null && analyzer.isAlive())

                    {
                        try {
                            analyzer.doStop();
                            analyzer.interrupt();
                        } catch (Exception e) {
                        }
                        try {
                            Thread.sleep(10);
                        } catch (Exception e) {
                        }
                    }
                    stopForeground(true);
                    notificationManager.cancel(1);
                }
            }.start();
            return START_STICKY;
        }

        if (intent != null && intent.getBooleanExtra("forceRunning", false)) {
            startThread();
        } else {
            startIfNeeded();
        }

        return START_STICKY;
    }

    protected void registerAlarm() {
        AlarmManager alarmManager =
            (AlarmManager)getApplicationContext().getSystemService(ALARM_SERVICE);
        final PendingIntent startService =
                PendingIntent.getActivity(
                    getApplicationContext(),
                    0,
                    new Intent(getApplicationContext(), AnalyzerService.class),
                    PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            0,
            60 * 60 * 1000,
            startService);
    }

    protected void startIfNeeded() {
        // collect battery level
        Intent batteryIntent = getApplicationContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int rawlevel = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        boolean onBattery =
                batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0;
        double level = -1;
        if (rawlevel >= 0 && scale > 0) {
            level = rawlevel / (double)scale;
        }

        // collect file information
        File path = getDatabasePath("contactsgraph");
        if (!path.exists()) {
            if (!path.mkdirs() && !path.isDirectory()) {
                // this is a bug, the app is not yet ready
                return;
            }
        }
        File graphFile = new File(path, "graph.kryo.gz");
        File modelFile = new File(path, "model.kryo.gz");
        boolean graphFileExists =
                graphFile.exists();
        boolean modelFileExists =
                modelFile.exists() && modelFile.lastModified() > graphFile.lastModified();

        long lastScan = 0l;
        if (scanPreferences != null) {
            lastScan = scanPreferences.getLong("start_scan", lastScan);
        }

        // 1. no data
        if (!modelFileExists && lastScan == 0l) {
            if (!graphFileExists) {
                Log.d(TAG, "Starting thread due to missing data");
                startThread();
                return;
            }
            try {
                ArrayList<MergeContact> model = GraphConverter.convert(
                        (UndirectedGraph) GraphIO.load(graphFile),
                        getBaseContext().getContentResolver().acquireContentProviderClient(ContactsContract.AUTHORITY_URI));
                ModelIO.store(model, modelFile);
                return; // only return if the conversion succeeded
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (level <= 0.25) {
            return;
        }

        double f = 1d;
        synchronized (rnd) {
            f += rnd.nextDouble();
        }

        lastScan = Math.max(lastScan, graphFile.lastModified());

        // 2. Plugged in, battery good -> go (bit not more often than once per 24 hours)
        if (!onBattery && level > 0.95) {
            if (lastScan + 7l * 24l * 60l * 60l * 1000l * f < System.currentTimeMillis()) {
                Log.d(TAG, "Starting thread due to good battery and old data");
                startThread();
                return;
            }
        }

        if (!onBattery && level > 0.75) {
            if (lastScan + 30l * 24l * 60l * 60l * 1000l * f < System.currentTimeMillis()) {
                Log.d(TAG, "Starting thread due to ok battery and old data");
                startThread();
                return;
            }
        }

        // 3. Really old data + plugged in?
        if (!onBattery && lastScan + 60l * 24l * 60l * 60l * 1000l * f < System.currentTimeMillis()) {
            Log.d(TAG, "Starting thread due to old data (on battery)");
            startThread();
            return;
        }

        // 4. we should only run on battery if everything else fails
        if (lastScan + 90l * 24l * 60l * 60l * 1000l * f < System.currentTimeMillis()) {
            Log.d(TAG, "Starting thread due to very old data (" + lastScan + ")");
            startThread();
            return;
        }
    }

    public synchronized void startThread() {
        if (analyzer != null && analyzer.isAlive()) return;
        analyzer = new AnalyzerThread(getApplicationContext());
        setupNotification();
        Intent intent = new Intent("de.measite.contactmerger.ANALYSE");
        intent.putExtra("event", "start");
        broadcastManager.sendBroadcast(intent);
        if (scanPreferences != null) {
            try {
                scanPreferences.edit().putLong("scan_start", System.currentTimeMillis()).commit();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        analyzer.start();
    }

    protected synchronized void setupNotification() {
        final PendingIntent startPending =
            PendingIntent.getActivity(
                getApplicationContext(),
                0,
                new Intent(getApplicationContext(), MergeActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setAction(Intent.ACTION_MAIN),
                PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification.Builder builder = 
            new Notification.Builder(getApplicationContext())
                .setSmallIcon(R.drawable.notification_icon)
                .setContentIntent(startPending)
                .setProgress(1000, 0, false)
                .setContentTitle("Analyzing your contacts")
                .setWhen(System.currentTimeMillis())
                .setContentText("0% done");
        startForeground(1, builder.build());

        final NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        analyzer.addListener(new ProgressListener() {
            long last = System.currentTimeMillis();
            @Override
            public void abort() {
                stopForeground(true);
                notificationManager.cancel(1);
                Intent intent = new Intent("de.measite.contactmerger.ANALYSE");
                intent.putExtra("event", "abort");
                broadcastManager.sendBroadcast(intent);
            }
            @Override
            public void update(float done) {
                builder.setProgress(1000, (int)(1000 * done), false);
                builder.setContentText(((int)(100 * done)) + "% done");
                if (System.currentTimeMillis() - last > 200) {
                    notificationManager.notify(1, builder.build());
                    last = System.currentTimeMillis();
                    Intent intent = new Intent("de.measite.contactmerger.ANALYSE");
                    intent.putExtra("event", "progress");
                    intent.putExtra("progress", done);
                    broadcastManager.sendBroadcast(intent);
                }
                if (done >= 1f) {
                    stopForeground(true);

                    File path = getApplicationContext().getDatabasePath("contactsgraph");
                    if (!path.exists()) path.mkdirs();
                    File modelFile = new File(path, "model.kryo.gz");
                    int count = 0;
                    try {
                        count = ModelIO.load(modelFile).size();
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    Intent intent = new Intent("de.measite.contactmerger.ANALYSE");
                    intent.putExtra("event", "finish");
                    broadcastManager.sendBroadcast(intent);

                    if (count > 0) {
                        Builder ibuilder =
                            new Notification.Builder(getApplicationContext())
                                .setSmallIcon(R.drawable.notification_icon)
                                .setContentIntent(startPending)
                                .setContentTitle("Merge " + count + " contacts")
                                .setWhen(System.currentTimeMillis())
                                .setContentText("All contacts were analyzed")
                                .setTicker("Merge " + count + " contacts")
                                .setAutoCancel(true);
                        notificationManager.notify(1, ibuilder.build());
                    } else {
                        notificationManager.cancel(1);
                    }
                }
            }
        });
    }

}
