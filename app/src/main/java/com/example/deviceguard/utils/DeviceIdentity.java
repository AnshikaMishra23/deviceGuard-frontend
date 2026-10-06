package com.example.deviceguard.utils;
import android.content.Context;import android.provider.Settings;
public final class DeviceIdentity { private DeviceIdentity(){} public static String get(Context c){String id=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID);return id==null?"unknown-android-device":id;} }
