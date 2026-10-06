package com.example.deviceguard.utils;
import android.content.Context;import android.os.BatteryManager;
public final class BatteryUtils { private BatteryUtils(){} public static int get(Context c){BatteryManager bm=(BatteryManager)c.getSystemService(Context.BATTERY_SERVICE);if(bm==null)return -1;int v=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);return (v>=0&&v<=100)?v:-1;} }
