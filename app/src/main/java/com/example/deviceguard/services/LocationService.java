package com.example.deviceguard.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.example.deviceguard.DashboardActivity;
import com.example.deviceguard.R;
import com.example.deviceguard.models.LocationResponse;
import com.example.deviceguard.network.RetrofitClient;
import com.example.deviceguard.utils.BatteryUtils;
import com.example.deviceguard.utils.SessionManager;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Foreground service that continues uploading the registered phone's GPS
 * location after the DeviceGuard activity is closed.
 */
public class LocationService extends Service {
    public static final String ACTION_STOP_LOCATION =
            "com.example.deviceguard.STOP_LOCATION";

    private static final int NOTIFICATION_ID = 41;
    private static final String CHANNEL_ID = "location";

    private FusedLocationProviderClient fused;
    private LocationCallback callback;

    @Override
    public void onCreate() {
        super.onCreate();
        makeChannel();
        fused = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());

        if (intent != null && ACTION_STOP_LOCATION.equals(intent.getAction())) {
            new SessionManager(this).setLocationSharing(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        SessionManager session = new SessionManager(this);
        if (!session.loggedIn() || session.getDeviceId() == null || !session.isLocationSharing()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        requestLocationUpdates();
        return START_STICKY;
    }

    private void requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            new SessionManager(this).setLocationSharing(false);
            stopSelf();
            return;
        }

        // Avoid duplicate callbacks when Android restarts/re-delivers the service command.
        if (callback != null) {
            fused.removeLocationUpdates(callback);
        }

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                30_000L
        )
                .setMinUpdateIntervalMillis(15_000L)
                .setMaxUpdateDelayMillis(60_000L)
                .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                for (Location location : result.getLocations()) {
                    sendLocation(location);
                }
            }
        };

        fused.requestLocationUpdates(request, callback, Looper.getMainLooper());
    }

    private void sendLocation(Location location) {
        SessionManager session = new SessionManager(this);
        String deviceId = session.getDeviceId();
        if (!session.loggedIn() || deviceId == null || !session.isLocationSharing()) {
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("latitude", location.getLatitude());
        body.put("longitude", location.getLongitude());
        body.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : null);
        body.put("batteryLevel", BatteryUtils.get(this));

        RetrofitClient.get(this).updateLocation(body).enqueue(new Callback<LocationResponse>() {
            @Override
            public void onResponse(Call<LocationResponse> call, Response<LocationResponse> response) {
                // Location + battery + lastSeen are stored by the backend.
            }

            @Override
            public void onFailure(Call<LocationResponse> call, Throwable t) {
                // The next GPS update retries automatically.
            }
        });
    }

    private Notification notification() {
        Intent stopIntent = new Intent(this, LocationService.class)
                .setAction(ACTION_STOP_LOCATION);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent openIntent = new Intent(this, DashboardActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                NOTIFICATION_ID + 1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("DeviceGuard location sharing")
                .setContentText("Your authorized phone is sharing its location.")
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build();
    }

    private void makeChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "DeviceGuard Location",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows while DeviceGuard is sharing this phone's location.");

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (callback != null) {
            fused.removeLocationUpdates(callback);
            callback = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
