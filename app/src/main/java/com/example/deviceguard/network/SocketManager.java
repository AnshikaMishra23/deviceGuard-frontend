package com.example.deviceguard.network;

import android.content.Context;

import com.example.deviceguard.utils.SessionManager;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Small wrapper around Socket.IO used by the foreground UI and map screen.
 * Command delivery and location subscriptions are deliberately separate:
 * device:<id> is for commands, location:<id> is for location updates.
 */
public class SocketManager {
    private Socket socket;
    private final Set<String> pendingLocationSubscriptions =
            Collections.synchronizedSet(new HashSet<>());

    public interface RingListener {
        void onRing();
    }

    public interface LocationListener {
        void onLocation(String deviceId, double lat, double lon);
    }

    public void connect(
            Context context,
            SessionManager session,
            RingListener ringListener,
            LocationListener locationListener
    ) {
        try {
            Map<String, String> auth = new HashMap<>();
            auth.put("token", session.getToken());
            if (session.getDeviceId() != null) {
                auth.put("deviceId", session.getDeviceId());
            }

            IO.Options options = IO.Options.builder()
                    .setAuth(auth)
                    .setReconnection(true)
                    .build();

            socket = IO.socket(ApiConfig.socketUrl(context), options);

            socket.on(Socket.EVENT_CONNECT, args -> resubscribeAll());

            socket.on("device:ring", args -> {
                if (ringListener == null || args.length == 0 || args[0] == null) {
                    return;
                }
                try {
                    JSONObject payload = (JSONObject) args[0];
                    String targetDeviceId = payload.optString("targetDeviceId", "");
                    String myDeviceId = session.getDeviceId();

                    // Defense in depth: only react if this socket is the target device.
                    if (myDeviceId != null && myDeviceId.equals(targetDeviceId)) {
                        ringListener.onRing();
                    }
                } catch (Exception ignored) {
                }
            });

            socket.on("location:update", args -> {
                if (locationListener != null && args.length > 0) {
                    try {
                        JSONObject root = (JSONObject) args[0];
                        JSONObject point = root.getJSONObject("location");
                        locationListener.onLocation(
                                point.getString("deviceId"),
                                point.getDouble("latitude"),
                                point.getDouble("longitude")
                        );
                    } catch (Exception ignored) {
                    }
                }
            });

            socket.connect();
        } catch (Exception ignored) {
        }
    }

    /**
     * Subscribe only to location updates for a device. The server puts this
     * socket into a location:<deviceId> room, never the command room.
     * Subscriptions are queued until Socket.IO is connected and are restored
     * after reconnects.
     */
    public void subscribe(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }
        pendingLocationSubscriptions.add(deviceId);
        if (socket != null && socket.connected()) {
            socket.emit("subscribeDevice", deviceId);
        }
    }

    private void resubscribeAll() {
        if (socket == null || !socket.connected()) {
            return;
        }
        synchronized (pendingLocationSubscriptions) {
            for (String deviceId : pendingLocationSubscriptions) {
                socket.emit("subscribeDevice", deviceId);
            }
        }
    }

    public void clearLocationSubscriptions() {
        if (socket != null && socket.connected()) {
            synchronized (pendingLocationSubscriptions) {
                for (String deviceId : pendingLocationSubscriptions) {
                    socket.emit("unsubscribeDevice", deviceId);
                }
            }
        }
        pendingLocationSubscriptions.clear();
    }

    /**
     * Used when this phone is registered after the socket was already connected.
     */
    public void identifyDevice(String deviceId) {
        if (socket != null && socket.connected() && deviceId != null && !deviceId.isEmpty()) {
            socket.emit("identifyDevice", deviceId);
        }
    }

    public void disconnect() {
        pendingLocationSubscriptions.clear();
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }
}
