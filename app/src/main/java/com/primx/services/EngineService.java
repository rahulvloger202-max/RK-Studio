package com.primx.services;

import com.primx.ui.mainactivity.MainActivity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class EngineService extends Service {

    public static final String ACTION_START = "com.primx.ACTION_START";
    public static final String ACTION_STOP = "com.primx.ACTION_STOP";
    public static final String ACTION_TOGGLE_WAKE = "com.primx.ACTION_TOGGLE_WAKE";
    public static final String ACTION_UPDATE_STATUS = "com.primx.ACTION_UPDATE_STATUS";

    public static final String EXTRA_ERRORS = "extra_errors";
    public static final String EXTRA_WARNINGS = "extra_warnings";

    private static final String CHANNEL_ID = "primx_background_engine";
    private static final int NOTIFICATION_ID = 1001;

    private static EngineService instance = null;
    private static PowerManager.WakeLock wakeLock = null;
    private static boolean isWakeLockHeld = false;
    private static int lastErrorCount = 0;
    private static int lastWarningCount = 0;

    public static void startService(Context context) {
        try {
            Intent intent = new Intent(context, EngineService.class);
            intent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {}
    }

    public static void stopService(Context context) {
        try {
            Intent intent = new Intent(context, EngineService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        } catch (Exception ignored) {}
    }

    public static void updateStatus(Context context, int errors, int warnings) {
        lastErrorCount = errors;
        lastWarningCount = warnings;
        if (instance != null) {
            instance.updateNotification();
        } else if (context != null) {
            try {
                Intent intent = new Intent(context, EngineService.class);
                intent.setAction(ACTION_UPDATE_STATUS);
                intent.putExtra(EXTRA_ERRORS, errors);
                intent.putExtra(EXTRA_WARNINGS, warnings);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        acquireWakeLock();
        updateNotification();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PrimX Background Engine",
                NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Keeps PrimX IDE running in background with wake lock");
                channel.setShowBadge(true);
                channel.setSound(null, null);
                channel.enableVibration(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private synchronized void acquireWakeLock() {
        if (wakeLock == null) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrimX:EngineWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            } catch (Exception ignored) {}
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire();
                isWakeLockHeld = true;
            } catch (Exception ignored) {}
        }
    }

    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                isWakeLockHeld = false;
            } catch (Exception ignored) {}
        }
    }

    /**
    * Status Bar Icon:
    * - Error ho: Red "X" with transparent background.
    * - Error na ho par Warning ho: Yellow "!" Triangle with transparent background.
    * - Sab clean ho: Green Tick with transparent background.
    */
    public static Bitmap createStatusBitmap(int errorCount, int warningCount) {
        int size = 64;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Path path = new Path();
        if (errorCount > 0) {
            // Red X (Error)
            paint.setStrokeWidth(7.5f);
            paint.setColor(Color.parseColor("#F44336"));
            path.moveTo(size * 0.25f, size * 0.25f);
            path.lineTo(size * 0.75f, size * 0.75f);
            path.moveTo(size * 0.75f, size * 0.25f);
            path.lineTo(size * 0.25f, size * 0.75f);
            canvas.drawPath(path, paint);
        } else if (warningCount > 0) {
            // Amber/Yellow Warning Triangle with Exclamation (!)
            paint.setColor(Color.parseColor("#FFC107"));
            paint.setStrokeWidth(6.0f);
            path.moveTo(size * 0.50f, size * 0.14f);
            path.lineTo(size * 0.88f, size * 0.82f);
            path.lineTo(size * 0.12f, size * 0.82f);
            path.close();
            canvas.drawPath(path, paint);

            paint.setStrokeWidth(5.0f);
            canvas.drawLine(size * 0.50f, size * 0.36f, size * 0.50f, size * 0.56f, paint);
            paint.setStrokeWidth(6.0f);
            canvas.drawPoint(size * 0.50f, size * 0.70f, paint);
        } else {
            // Green Checkmark Tick (Success / Clean)
            paint.setStrokeWidth(7.5f);
            paint.setColor(Color.parseColor("#4CAF50"));
            path.moveTo(size * 0.20f, size * 0.52f);
            path.lineTo(size * 0.44f, size * 0.74f);
            path.lineTo(size * 0.82f, size * 0.26f);
            canvas.drawPath(path, paint);
        }
        return bitmap;
    }

    public static Bitmap createStatusBitmap(boolean hasError) {
        return createStatusBitmap(hasError ? 1 : 0, 0);
    }

    public void updateNotification() {
        try {
            Intent openIntent = new Intent(this, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            : PendingIntent.FLAG_UPDATE_CURRENT;

            PendingIntent pOpenIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

            Intent wakeIntent = new Intent(this, EngineService.class);
            wakeIntent.setAction(ACTION_TOGGLE_WAKE);
            PendingIntent pWakeIntent = PendingIntent.getService(this, 1, wakeIntent, flags);

            Intent stopIntent = new Intent(this, EngineService.class);
            stopIntent.setAction(ACTION_STOP);
            PendingIntent pStopIntent = PendingIntent.getService(this, 2, stopIntent, flags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            boolean hasErrors = lastErrorCount > 0;
            boolean hasWarnings = lastWarningCount > 0;

            // Status Bar Icon: Transparent Background ke sath Red X, Yellow Triangle ya Green Tick
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Bitmap statusBmp = createStatusBitmap(lastErrorCount, lastWarningCount);
                builder.setSmallIcon(Icon.createWithBitmap(statusBmp));
            } else {
                int iconId = getResources().getIdentifier("app_logo", "drawable", getPackageName());
                if (iconId == 0) iconId = android.R.drawable.ic_popup_sync;
                builder.setSmallIcon(iconId);
            }

            builder.setContentTitle("PrimX-IDE");

            String errorStatusText;
            if (hasErrors) {
                errorStatusText = lastErrorCount + (lastErrorCount == 1 ? " error found" : " errors found");
                if (hasWarnings) {
                    errorStatusText += " (" + lastWarningCount + (lastWarningCount == 1 ? " warning)" : " warnings)");
                }
            } else if (hasWarnings) {
                errorStatusText = lastWarningCount + (lastWarningCount == 1 ? " warning found" : " warnings found");
            } else {
                errorStatusText = "No errors";
            }

            String wakeText = isWakeLockHeld ? "Wake lock held" : "Wake lock released";
            builder.setContentText(errorStatusText + " • " + wakeText);

            builder.setContentIntent(pOpenIntent);
            builder.setOngoing(true);

            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", pStopIntent);
            String toggleText = isWakeLockHeld ? "Release wakelock" : "Acquire wakelock";
            builder.addAction(android.R.drawable.ic_lock_power_off, toggleText, pWakeIntent);

            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                releaseWakeLock();
                try { stopForeground(true); } catch (Exception ignored) {}
                stopSelf();
                android.os.Process.killProcess(android.os.Process.myPid());
                return START_NOT_STICKY;
            } else if (ACTION_TOGGLE_WAKE.equals(action)) {
                if (isWakeLockHeld) {
                    releaseWakeLock();
                } else {
                    acquireWakeLock();
                }
                updateNotification();
                return START_STICKY;
            } else if (ACTION_UPDATE_STATUS.equals(action)) {
                lastErrorCount = intent.getIntExtra(EXTRA_ERRORS, lastErrorCount);
                lastWarningCount = intent.getIntExtra(EXTRA_WARNINGS, lastWarningCount);
                updateNotification();
                return START_STICKY;
            }
        }
        acquireWakeLock();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        releaseWakeLock();
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
        // EngineService.java में सबसे नीचे इसे जोड़ें
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 🔥 FIX: जब यूजर रीसेंट्स से ऐप हटाए, तो सर्विस को बंद मत करो!
        // Notification को अपडेट करके बैकग्राउंड में ज़िंदा रहो
        updateNotification();
        super.onTaskRemoved(rootIntent);
    }

}
