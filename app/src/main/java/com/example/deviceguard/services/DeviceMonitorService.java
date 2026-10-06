package com.example.deviceguard.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.Ringtone;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.deviceguard.DashboardActivity;
import com.example.deviceguard.models.DeviceResponse;
import com.example.deviceguard.network.ApiService;
import com.example.deviceguard.network.RetrofitClient;
import com.example.deviceguard.network.SocketManager;
import com.example.deviceguard.utils.BatteryUtils;
import com.example.deviceguard.utils.SessionManager;

import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Keeps the registered phone reachable when the DeviceGuard UI is closed.
 * It maintains the Socket.IO command connection and periodically sends the
 * phone's battery level/heartbeat to the local backend.
 */
public class DeviceMonitorService extends Service {
    public static final String ACTION_STOP_RING = "com.example.deviceguard.STOP_RING";

    private static final int NOTIFICATION_ID = 52;
    private static final int RING_NOTIFICATION_ID = 53;
    private static final String CHANNEL_MONITOR = "deviceguard_monitor";
    private static final String CHANNEL_RING = "deviceguard_ring";
    private static final long HEARTBEAT_MS = 60_000L;
    private static final long RING_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SessionManager session;
    private ApiService api;
    private SocketManager socket;
    private String connectedDeviceId;
    private MediaPlayer ringPlayer;
    private Ringtone fallbackRingtone;

    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            heartbeat();
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private final Runnable stopRingTask = this::stopRing;

    @Override
    public void onCreate() {
        super.onCreate();
        session = new SessionManager(this);
        api = RetrofitClient.get(this);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildMonitorNotification());

        if (intent != null && ACTION_STOP_RING.equals(intent.getAction())) {
            stopRing();
        }

        ensureSocket();
        heartbeat();
        handler.removeCallbacks(heartbeatTask);
        handler.postDelayed(heartbeatTask, HEARTBEAT_MS);

        return START_STICKY;
    }

    private void ensureSocket() {
        if (!session.loggedIn()) {
            disconnectSocket();
            return;
        }

        String deviceId = session.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            disconnectSocket();
            return;
        }

        if (socket != null && deviceId.equals(connectedDeviceId)) {
            return;
        }

        disconnectSocket();
        socket = new SocketManager();
        connectedDeviceId = deviceId;
        socket.connect(
                this,
                session,
                this::handleRing,
                null
        );
    }

    private void heartbeat() {
        if (!session.loggedIn() || session.getDeviceId() == null) {
            ensureSocket();
            return;
        }

        ensureSocket();

        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", session.getDeviceId());
        body.put("batteryLevel", BatteryUtils.get(this));

        api.heartbeat(body).enqueue(new Callback<DeviceResponse>() {
            @Override
            public void onResponse(Call<DeviceResponse> call, Response<DeviceResponse> response) {
                // The backend updates lastSeen and battery even when the UI is closed.
            }

            @Override
            public void onFailure(Call<DeviceResponse> call, Throwable t) {
                // Socket.IO will reconnect automatically when the backend becomes reachable.
            }
        });
    }

    private void handleRing() {
        handler.post(() -> {
            stopRing();

            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (ringtoneUri == null) {
                ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }

            try {
                MediaPlayer player = new MediaPlayer();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    player.setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                    );
                }
                player.setDataSource(this, ringtoneUri);
                player.setLooping(true);
                player.prepare();
                ringPlayer = player;
                ringPlayer.start();
            } catch (Exception mediaPlayerError) {
                try {
                    fallbackRingtone = RingtoneManager.getRingtone(this, ringtoneUri);
                    if (fallbackRingtone != null) {
                        fallbackRingtone.play();
                    }
                } catch (Exception ignored) {
                }
            }

            showRingNotification();
            handler.removeCallbacks(stopRingTask);
            handler.postDelayed(stopRingTask, RING_MS);
        });
    }

    private void stopRing() {
        handler.removeCallbacks(stopRingTask);
        if (ringPlayer != null) {
            try {
                if (ringPlayer.isPlaying()) {
                    ringPlayer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                ringPlayer.release();
            } catch (Exception ignored) {
            }
            ringPlayer = null;
        }

        if (fallbackRingtone != null) {
            try {
                if (fallbackRingtone.isPlaying()) {
                    fallbackRingtone.stop();
                }
            } catch (Exception ignored) {
            }
            fallbackRingtone = null;
        }

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(RING_NOTIFICATION_ID);
        }
    }

    private void showRingNotification() {
        Intent stopIntent = new Intent(this, DeviceMonitorService.class)
                .setAction(ACTION_STOP_RING);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                RING_NOTIFICATION_ID,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_RING)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("DeviceGuard • Ringing")
                .setContentText("An authorized paired device requested this phone to ring.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(RING_NOTIFICATION_ID, notification);
        }
    }

    private Notification buildMonitorNotification() {
        Intent openIntent = new Intent(this, DashboardActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("DeviceGuard is active")
                .setContentText("Device status, battery and remote commands are being monitored.")
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR,
                "DeviceGuard Background Monitoring",
                NotificationManager.IMPORTANCE_LOW
        );
        monitor.setDescription("Keeps DeviceGuard available for battery and remote device commands.");

        NotificationChannel ring = new NotificationChannel(
                CHANNEL_RING,
                "DeviceGuard Ring Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        ring.setDescription("Alerts when an authorized paired DeviceGuard phone requests a ring.");
        ring.setSound(null, null);

        manager.createNotificationChannel(monitor);
        manager.createNotificationChannel(ring);
    }

    private void disconnectSocket() {
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
        connectedDeviceId = null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(heartbeatTask);
        stopRing();
        disconnectSocket();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
