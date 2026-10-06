package com.example.deviceguard;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.deviceguard.models.CodeResponse;
import com.example.deviceguard.models.Device;
import com.example.deviceguard.models.DeviceResponse;
import com.example.deviceguard.models.Pairing;
import com.example.deviceguard.models.PairingsResponse;
import com.example.deviceguard.models.DevicesResponse;
import com.example.deviceguard.models.MessageResponse;
import com.example.deviceguard.utils.ApiError;
import com.example.deviceguard.network.ApiService;
import com.example.deviceguard.network.RetrofitClient;
import com.example.deviceguard.network.SocketManager;
import com.example.deviceguard.services.DeviceMonitorService;
import com.example.deviceguard.services.LocationService;
import com.example.deviceguard.utils.BatteryUtils;
import com.example.deviceguard.utils.DeviceIdentity;
import com.example.deviceguard.utils.SessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DashboardActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST = 88;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 89;

    private SessionManager session;
    private ApiService api;
    private LinearLayout mine;
    private LinearLayout paired;
    private TextView status;
    private TextView userText;
    private SocketManager socket;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing()) {
                load();
                refreshHandler.postDelayed(this, 60_000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_dashboard);

        session = new SessionManager(this);
        api = RetrofitClient.get(this);

        bind();

        // The service owns background commands/heartbeat so it survives UI closure.
        startMonitorService();
        restoreLocationSharing();
        requestNotificationPermission();

        socket = new SocketManager();
        socket.connect(this, session, null, (id, lat, lon) -> runOnUiThread(this::load));
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshHandler.removeCallbacks(refreshTask);
        refreshHandler.postDelayed(refreshTask, 60_000L);
    }

    @Override
    protected void onStop() {
        refreshHandler.removeCallbacks(refreshTask);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null && session.loggedIn()) {
            load();
        }
    }

    private void bind() {
        status = findViewById(R.id.status);
        userText = findViewById(R.id.userText);
        mine = findViewById(R.id.myDevices);
        paired = findViewById(R.id.pairedDevices);

        findViewById(R.id.refresh).setOnClickListener(v -> load());
        findViewById(R.id.createCode).setOnClickListener(v -> createCode());
        findViewById(R.id.join).setOnClickListener(v -> join());
        findViewById(R.id.bluetooth).setOnClickListener(v ->
                startActivity(new Intent(this, BluetoothActivity.class)));
        findViewById(R.id.serverSettings).setOnClickListener(v -> serverDialog());
        findViewById(R.id.registerDevice).setOnClickListener(v -> registerDevice());

        findViewById(R.id.logout).setOnClickListener(v -> {
            stopService(new Intent(this, DeviceMonitorService.class));
            stopService(new Intent(this, LocationService.class));
            if (socket != null) {
                socket.disconnect();
            }
            session.logout();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        updateIdentityText();
    }

    private void updateIdentityText() {
        userText.setText(
                "Signed in • device ID: "
                        + (session.getDeviceId() == null ? "not registered" : session.getDeviceId())
        );
    }

    private void load() {
        status.setText("Loading devices…");

        api.devices().enqueue(new Callback<DevicesResponse>() {
            @Override
            public void onResponse(Call<DevicesResponse> call, Response<DevicesResponse> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    status.setText(ApiError.message(response));
                    return;
                }

                List<Device> devices = response.body().devices == null
                        ? new ArrayList<>()
                        : response.body().devices;
                renderMine(devices);

                api.pairings().enqueue(new Callback<PairingsResponse>() {
                    @Override
                    public void onResponse(Call<PairingsResponse> call, Response<PairingsResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            List<Pairing> pairings = response.body().pairings == null
                                    ? new ArrayList<>()
                                    : response.body().pairings;
                            renderPaired(pairings);
                        } else {
                            status.setText(ApiError.message(response));
                        }
                    }

                    @Override
                    public void onFailure(Call<PairingsResponse> call, Throwable t) {
                        status.setText("Pairings error: " + t.getMessage());
                    }
                });
            }

            @Override
            public void onFailure(Call<DevicesResponse> call, Throwable t) {
                status.setText("Network error: " + t.getMessage());
            }
        });
    }

    private void renderMine(List<Device> devices) {
        mine.removeAllViews();
        if (devices.isEmpty()) {
            empty(mine, "No device registered yet.");
        }

        String myDeviceId = session.getDeviceId();
        for (Device device : devices) {
            if (myDeviceId != null && myDeviceId.equals(device._id)) {
                device.batteryLevel = BatteryUtils.get(this);
                heartbeat(device);
            }
            addCard(mine, device, false);
        }
    }

    private void renderPaired(List<Pairing> pairings) {
        paired.removeAllViews();
        if (socket != null) {
            socket.clearLocationSubscriptions();
        }

        Set<String> seen = new HashSet<>();
        String userId = session.getUserId();

        for (Pairing pairing : pairings) {
            if (pairing == null
                    || pairing.controllerDeviceId == null
                    || pairing.trackedDeviceId == null) {
                continue;
            }

            Device other;
            if (userId != null && userId.equals(pairing.controllerDeviceId.userId)) {
                other = pairing.trackedDeviceId;
            } else {
                other = pairing.controllerDeviceId;
            }

            if (other != null && other._id != null && seen.add(other._id)) {
                addCard(paired, other, true);
                if (socket != null) {
                    socket.subscribe(other._id);
                }
            }
        }

        if (seen.isEmpty()) {
            empty(paired, "No paired devices yet. Use Create pairing code / Join another device.");
        }

        status.setText("Ready • " + seen.size() + " paired device(s)");
    }

    private void addCard(LinearLayout parent, Device device, boolean isPaired) {
        View view = getLayoutInflater().inflate(R.layout.item_device, parent, false);
        TextView name = view.findViewById(R.id.deviceName);
        TextView info = view.findViewById(R.id.deviceInfo);
        Button viewButton = view.findViewById(R.id.view);
        Button actionButton = view.findViewById(R.id.action);
        Button historyButton = view.findViewById(R.id.history);

        name.setText(device.deviceName);
        info.setText(
                ("ONLINE".equals(device.status) ? "● Online" : "○ Offline")
                        + "  •  Battery: "
                        + (device.batteryLevel < 0 ? "Unknown" : device.batteryLevel + "%")
        );

        viewButton.setOnClickListener(v -> openDetails(device, isPaired));
        historyButton.setOnClickListener(v -> startActivity(
                new Intent(this, HistoryActivity.class)
                        .putExtra("deviceId", device._id)
                        .putExtra("deviceName", device.deviceName)
        ));

        if (isPaired) {
            actionButton.setText("Ring");
            actionButton.setOnClickListener(v -> ring(device._id));
        } else {
            actionButton.setText("Location");
            actionButton.setOnClickListener(v -> toggleLocation());
        }

        parent.addView(view);
    }

    private void openDetails(Device device, boolean pairedDevice) {
        startActivity(new Intent(this, DeviceDetailsActivity.class)
                .putExtra("deviceId", device._id)
                .putExtra("deviceName", device.deviceName)
                .putExtra("paired", pairedDevice));
    }

    private void empty(LinearLayout layout, String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setPadding(8, 12, 8, 12);
        layout.addView(text);
    }

    private void createCode() {
        String deviceId = session.getDeviceId();
        if (deviceId == null) {
            toast("Register this phone first.");
            return;
        }

        Map<String, String> body = new HashMap<>();
        body.put("deviceId", deviceId);

        api.createPairingCode(body).enqueue(new Callback<CodeResponse>() {
            @Override
            public void onResponse(Call<CodeResponse> call, Response<CodeResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    new AlertDialog.Builder(DashboardActivity.this)
                            .setTitle("Pairing code")
                            .setMessage(
                                    "Share this code with the other authorized DeviceGuard phone:\n\n"
                                            + response.body().code
                                            + "\n\nExpires in 10 minutes."
                            )
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    toast(ApiError.message(response));
                }
            }

            @Override
            public void onFailure(Call<CodeResponse> call, Throwable t) {
                toast("Network error: " + t.getMessage());
            }
        });
    }

    private void join() {
        if (session.getDeviceId() == null) {
            toast("Register this phone first.");
            return;
        }

        EditText input = new EditText(this);
        input.setHint("6-digit code");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        new AlertDialog.Builder(this)
                .setTitle("Join another device")
                .setView(input)
                .setPositiveButton("Pair", (dialog, which) -> {
                    Map<String, String> body = new HashMap<>();
                    body.put("deviceId", session.getDeviceId());
                    body.put("code", input.getText().toString().trim());

                    api.joinPairing(body).enqueue(new Callback<Map<String, Pairing>>() {
                        @Override
                        public void onResponse(Call<Map<String, Pairing>> call, Response<Map<String, Pairing>> response) {
                            if (response.isSuccessful()) {
                                toast("✓ Paired successfully");
                                load();
                            } else {
                                toast(ApiError.message(response));
                            }
                        }

                        @Override
                        public void onFailure(Call<Map<String, Pairing>> call, Throwable t) {
                            toast("Network error: " + t.getMessage());
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void ring(String deviceId) {
        Map<String, String> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("requesterDeviceId", session.getDeviceId());

        api.ring(body).enqueue(new Callback<MessageResponse>() {
            @Override
            public void onResponse(Call<MessageResponse> call, Response<MessageResponse> response) {
                toast(response.isSuccessful() ? "✓ Ring command sent" : ApiError.message(response));
            }

            @Override
            public void onFailure(Call<MessageResponse> call, Throwable t) {
                toast("Network error: " + t.getMessage());
            }
        });
    }

    private void heartbeat(Device device) {
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", device._id);
        body.put("batteryLevel", BatteryUtils.get(this));

        api.heartbeat(body).enqueue(new Callback<DeviceResponse>() {
            @Override
            public void onResponse(Call<DeviceResponse> call, Response<DeviceResponse> response) {
            }

            @Override
            public void onFailure(Call<DeviceResponse> call, Throwable t) {
            }
        });
    }

    private void toggleLocation() {
        String deviceId = session.getDeviceId();
        if (deviceId == null) {
            toast("Register this phone first.");
            return;
        }

        if (session.isLocationSharing()) {
            stopService(new Intent(this, LocationService.class));
            session.setLocationSharing(false);
            toast("Location sharing stopped");
        } else {
            startLocationWithPermissions();
        }
    }

    private void startLocationWithPermissions() {
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
        startForegroundServiceCompat(LocationService.class);
        toast("Location sharing started");
    }

    private void restoreLocationSharing() {
        if (!session.isLocationSharing()) {
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

        if (fineGranted || coarseGranted) {
            startForegroundServiceCompat(LocationService.class);
        } else {
            session.setLocationSharing(false);
        }
    }

    private void startMonitorService() {
        if (!session.loggedIn() || session.getDeviceId() == null) {
            return;
        }
        startForegroundServiceCompat(DeviceMonitorService.class);
    }

    private void startForegroundServiceCompat(Class<? extends android.app.Service> serviceClass) {
        Intent intent = new Intent(this, serviceClass);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private void serverDialog() {
        EditText input = new EditText(this);
        input.setText(session.getServerUrl());
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("Backend server URL")
                .setMessage("Example: http://192.168.1.20:5000")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    session.setServerUrl(input.getText().toString());
                    RetrofitClient.reset();
                    api = RetrofitClient.get(this);

                    stopService(new Intent(this, DeviceMonitorService.class));
                    startMonitorService();

                    if (socket != null) {
                        socket.disconnect();
                    }
                    socket = new SocketManager();
                    socket.connect(this, session, null, (id, lat, lon) -> runOnUiThread(this::load));
                    load();
                    toast("Server URL saved");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void registerDevice() {
        EditText input = new EditText(this);
        input.setHint("Device name, e.g. My Phone");

        new AlertDialog.Builder(this)
                .setTitle("Register this phone")
                .setView(input)
                .setPositiveButton("Register", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("Enter a device name");
                        return;
                    }

                    Map<String, String> body = new HashMap<>();
                    body.put("deviceName", name);
                    body.put("deviceIdentifier", DeviceIdentity.get(this));

                    api.createDevice(body).enqueue(new Callback<DeviceResponse>() {
                        @Override
                        public void onResponse(Call<DeviceResponse> call, Response<DeviceResponse> response) {
                            if (response.isSuccessful()
                                    && response.body() != null
                                    && response.body().device != null) {
                                session.setDeviceId(response.body().device._id);
                                updateIdentityText();
                                startMonitorService();
                                toast("✓ Phone registered successfully");
                                load();
                            } else {
                                toast(ApiError.message(response));
                            }
                        }

                        @Override
                        public void onFailure(Call<DeviceResponse> call, Throwable t) {
                            toast("Network error: " + t.getMessage());
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            boolean granted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            if (granted) {
                startLocationWithPermissions();
            } else {
                toast("Location permission is required for location sharing");
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (socket != null) {
            socket.disconnect();
        }
        super.onDestroy();
    }
}
