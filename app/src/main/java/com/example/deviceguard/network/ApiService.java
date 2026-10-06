package com.example.deviceguard.network;
import com.example.deviceguard.models.*;import java.util.Map;import retrofit2.Call;import retrofit2.http.*;
public interface ApiService {
 @GET("health") Call<MessageResponse> health();
 @POST("auth/register") Call<AuthResponse> register(@Body Map<String,String> body); @POST("auth/login") Call<AuthResponse> login(@Body Map<String,String> body);
 @GET("devices") Call<DevicesResponse> devices(); @POST("devices") Call<DeviceResponse> createDevice(@Body Map<String,String> body); @GET("devices/{id}") Call<DeviceResponse> getDevice(@Path("id")String id); @POST("devices/heartbeat") Call<DeviceResponse> heartbeat(@Body Map<String,Object> body); @DELETE("devices/{id}") Call<MessageResponse> deleteDevice(@Path("id")String id);
 @GET("devices/paired") Call<PairingsResponse> pairings();
 @POST("pairing/create") Call<CodeResponse> createPairingCode(@Body Map<String,String> body); @POST("pairing/join") Call<Map<String,Pairing>> joinPairing(@Body Map<String,String> body); @DELETE("pairing/{id}") Call<MessageResponse> unpair(@Path("id")String id);
 @POST("location/update") Call<LocationResponse> updateLocation(@Body Map<String,Object> body); @GET("location/{deviceId}") Call<LocationResponse> latestLocation(@Path("deviceId")String id); @GET("location/{deviceId}/history") Call<LocationsResponse> history(@Path("deviceId")String id,@Query("limit")int limit);
 @POST("commands/ring") Call<MessageResponse> ring(@Body Map<String,String> body);
}
