package com.example.deviceguard.utils;
import com.example.deviceguard.models.MessageResponse;import com.google.gson.Gson;import retrofit2.Response;
public final class ApiError { private ApiError(){} public static String message(Response<?> r){try{if(r.errorBody()!=null){MessageResponse m=new Gson().fromJson(r.errorBody().string(),MessageResponse.class);if(m!=null&&m.message!=null)return m.message;}}catch(Exception ignored){}return "Request failed (HTTP "+r.code()+")";} }
