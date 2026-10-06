package com.example.deviceguard.utils;
import android.content.Context;import android.content.SharedPreferences;
public class SessionManager {
 private final SharedPreferences p;
 public SessionManager(Context c){p=c.getSharedPreferences("deviceguard_session",Context.MODE_PRIVATE);}
 public void save(String token,String userId){p.edit().putString("token",token).putString("userId",userId).apply();}
 public String getToken(){return p.getString("token",null);} public String getUserId(){return p.getString("userId",null);}
 public void setDeviceId(String id){p.edit().putString("deviceId",id).apply();} public String getDeviceId(){return p.getString("deviceId",null);}
 public boolean loggedIn(){return getToken()!=null&&!getToken().isEmpty();}
 public String getServerUrl(){return p.getString("serverUrl","http://10.0.2.2:5000");}
 public void setServerUrl(String url){p.edit().putString("serverUrl",normalize(url)).apply();}
 private String normalize(String u){if(u==null)return "http://10.0.2.2:5000";String x=u.trim();while(x.endsWith("/"))x=x.substring(0,x.length()-1);if(x.endsWith("/api"))x=x.substring(0,x.length()-4);return x;}
 public boolean isLocationSharing(){return p.getBoolean("locationSharing",false);} public void setLocationSharing(boolean on){p.edit().putBoolean("locationSharing",on).apply();}
 public void logout(){p.edit().remove("token").remove("userId").remove("deviceId").remove("locationSharing").apply();}
}
