package com.example.deviceguard;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.deviceguard.models.Device;
import com.example.deviceguard.models.DeviceResponse;
import com.example.deviceguard.models.LocationPoint;
import com.example.deviceguard.models.LocationResponse;
import com.example.deviceguard.models.MessageResponse;
import com.example.deviceguard.models.Pairing;
import com.example.deviceguard.models.PairingsResponse;
import com.example.deviceguard.network.ApiService;
import com.example.deviceguard.network.RetrofitClient;
import com.example.deviceguard.network.SocketManager;
import com.example.deviceguard.services.DeviceMonitorService;
import com.example.deviceguard.services.LocationService;
import com.example.deviceguard.utils.ApiError;
import com.example.deviceguard.utils.SessionManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DeviceDetailsActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST = 12;

    private SessionManager session;
    private ApiService api;
    private String deviceId;
    private String deviceName;
    private boolean paired;

    private TextView name;
    private TextView status;
    private TextView battery;
    private TextView lastSeen;
    private TextView coords;
    private Button locationAction;
    private SocketManager socket;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_device_details);

        session = new SessionManager(this);
        api = RetrofitClient.get(this);
        deviceId = getIntent().getStringExtra("deviceId");
        deviceName = getIntent().getStringExtra("deviceName");
        paired = getIntent().getBooleanExtra("paired", false);

        bind();
        startMonitorService();
        load();

        socket = new SocketManager();
        socket.connect(
                this,
                session,
                null,
                (id, lat, lon) -> {
                    if (deviceId != null && deviceId.equals(id)) {
                        runOnUiThread(() -> coords.setText(
                                String.format(Locale.US, "%.6f, %.6f", lat, lon)
                        ));
                    }
                }
        );
        socket.subscribe(deviceId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (api != null && deviceId != null) {
            load();
        }
    }

    private void bind() {
        name = findViewById(R.id.name);
        status = findViewById(R.id.status);
        battery = findViewById(R.id.battery);
        lastSeen = findViewById(R.id.lastSeen);
        coords = findViewById(R.id.coords);
        locationAction = findViewById(R.id.locationAction);

        findViewById(R.id.viewMap).setOnClickListener(v -> startActivity(
                new Intent(this, MapActivity.class)
                        .putExtra("deviceId", deviceId)
                        .putExtra("deviceName", deviceName)
        ));
        findViewById(R.id.history).setOnClickListener(v -> startActivity(
                new Intent(this, HistoryActivity.class)
                        .putExtra("deviceId", deviceId)
                        .putExtra("deviceName", deviceName)
        ));
        findViewById(R.id.ring).setOnClickListener(v -> ring());
        findViewById(R.id.unpair).setOnClickListener(v -> unpair());
        findViewById(R.id.back).setOnClickListener(v -> finish());
    }

    private void load() {
        api.getDevice(deviceId).enqueue(new Callback<DeviceResponse>() {
            @Override
            public void onResponse(Call<DeviceResponse> call, Response<DeviceResponse> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().device == null) {
                    status.setText(ApiError.message(response));
                    return;
                }

                Device device = response.body().device;
                deviceName = device.deviceName;
                name.setText(device.deviceName);
                status.setText("Status: " + ("ONLINE".equals(device.status) ? "Online" : "Offline"));
                battery.setText(
                        "Battery: "
                                + (device.batteryLevel < 0 ? "Unknown" : device.batteryLevel + "%")
                );
                lastSeen.setText("Last seen: " + (device.lastSeen == null ? "Never" : device.lastSeen));

                locationAction.setVisibility(paired ? View.GONE : View.VISIBLE);
                locationAction.setText(
                        session.isLocationSharing()
                                ? "Stop Location Sharing"
                                : "Start Location Sharing"
                );
                locationAction.setOnClickListener(v -> toggleSharing());

                findViewById(R.id.ring).setVisibility(paired ? View.VISIBLE : View.GONE);
                findViewById(R.id.unpair).setVisibility(paired ? View.VISIBLE : View.GONE);

                loadLatest();
            }

            @Override
            public void onFailure(Call<DeviceResponse> call, Throwable t) {
                status.setText("Network error: " + t.getMessage());
            }
        });
    }

    private void loadLatest() {
        api.latestLocation(deviceId).enqueue(new Callback<LocationResponse>() {
            @Override
            public void onResponse(Call<LocationResponse> call, Response<LocationResponse> response) {
                if (response.isSuccessful()
                        && response.body() != null
                        && response.body().location != null) {
                    LocationPoint point = response.body().location;
                    coords.setText(String.format(
                            Locale.US,
                            "%.6f, %.6f",
                            point.latitude,
                            point.longitude
                    ));
                } else {
                    coords.setText("No location yet");
                }
            }

            @Override
            public void onFailure(Call<LocationResponse> call, Throwable t) {
                coords.setText("Location unavailable");
            }
        });
    }

    private void ring() {
        Map<String, String> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("requesterDeviceId", session.getDeviceId());

        api.ring(body).enqueue(new Callback<MessageResponse>() {
            @Override
            public void onResponse(Call<MessageResponse> call, Response<MessageResponse> response) {
                Toast.makeText(
                        DeviceDetailsActivity.this,
                        response.isSuccessful() ? "✓ Ring command sent" : ApiError.message(response),
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onFailure(Call<MessageResponse> call, Throwable t) {
                Toast.makeText(
                        DeviceDetailsActivity.this,
                        "Network error: " + t.getMessage(),
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
    }

    private void unpair() {
        new AlertDialog.Builder(this)
                .setTitle("Unpair device?")
                .setMessage("This removes the authorization relationship.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unpair", (dialog, which) -> findPairingAndDelete())
                .show();
    }

    private void findPairingAndDelete() {
        api.pairings().enqueue(new Callback<PairingsResponse>() {
            @Override
            public void onResponse(Call<PairingsResponse> call, Response<PairingsResponse> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().pairings == null) {
                    Toast.makeText(
                            DeviceDetailsActivity.this,
                            ApiError.message(response),
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                for (Pairing pairing : response.body().pairings) {
                    if (pairing == null
                            || pairing._id == null
                            || pairing.controllerDeviceId == null
                            || pairing.trackedDeviceId == null) {
                        continue;
                    }

                    String controllerId = pairing.controllerDeviceId._id;
                    String trackedId = pairing.trackedDeviceId._id;
                    if (deviceId.equals(controllerId) || deviceId.equals(trackedId)) {
                        deletePairing(pairing._id);
                        return;
                    }
                }

                Toast.makeText(
                        DeviceDetailsActivity.this,
                        "Pairing not found",
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onFailure(Call<PairingsResponse> call, Throwable t) {
                Toast.makeText(
                        DeviceDetailsActivity.this,
                        "Network error: " + t.getMessage(),
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
    }

    private void deletePairing(String pairingId) {
        api.unpair(pairingId).enqueue(new Callback<MessageResponse>() {
            @Override
            public void onResponse(Call<MessageResponse> call, Response<MessageResponse> response) {
                Toast.makeText(
                        DeviceDetailsActivity.this,
                        response.isSuccessful() ? "Unpaired" : ApiError.message(response),
                        Toast.LENGTH_SHORT
                ).show();
                if (response.isSuccessful()) {
                    finish();
                }
            }

            @Override
            public void onFailure(Call<MessageResponse> call, Throwable t) {
                Toast.makeText(
                        DeviceDetailsActivity.this,
                        "Network error: " + t.getMessage(),
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
    }

    private void toggleSharing() {
        if (session.isLocationSharing()) {
            stopService(new Intent(this, LocationService.class));
            session.setLocationSharing(false);
            locationAction.setText("Start Location Sharing");
            Toast.makeText(this, "Location sharing stopped", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean fineGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );
            return;
        }

        session.setLocationSharing(true);
        startLocationService();
        locationAction.setText("Stop Location Sharing");
        Toast.makeText(this, "Location sharing started", Toast.LENGTH_SHORT).show();
    }

    private void startLocationService() {
        Intent intent = new Intent(this, LocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, DeviceMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) {
            return;
        }

        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }

        if (granted) {
            toggleSharing();
        } else {
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (socket != null) {
            socket.disconnect();
        }
        super.onDestroy();
    }
}
