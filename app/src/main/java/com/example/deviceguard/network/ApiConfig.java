package com.example.deviceguard.network;
import android.content.Context;import com.example.deviceguard.utils.SessionManager;
public final class ApiConfig { private ApiConfig(){} public static String baseUrl(Context c){return new SessionManager(c).getServerUrl()+"/api/";} public static String socketUrl(Context c){return new SessionManager(c).getServerUrl();} }
