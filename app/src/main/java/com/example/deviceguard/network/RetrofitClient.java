package com.example.deviceguard.network;
import android.content.Context;import com.example.deviceguard.utils.SessionManager;import okhttp3.Interceptor;import okhttp3.OkHttpClient;import okhttp3.Request;import okhttp3.logging.HttpLoggingInterceptor;import retrofit2.Retrofit;import retrofit2.converter.gson.GsonConverterFactory;
public final class RetrofitClient { private static ApiService service;private static String base;
 private RetrofitClient(){}
 public static synchronized ApiService get(Context c){String wanted=ApiConfig.baseUrl(c);if(service==null||!wanted.equals(base)){SessionManager s=new SessionManager(c);Interceptor auth=chain->{Request.Builder b=chain.request().newBuilder();String t=s.getToken();if(t!=null&&!t.isEmpty())b.header("Authorization","Bearer "+t);return chain.proceed(b.build());};HttpLoggingInterceptor log=new HttpLoggingInterceptor();log.setLevel(HttpLoggingInterceptor.Level.BASIC);OkHttpClient client=new OkHttpClient.Builder().addInterceptor(auth).addInterceptor(log).build();service=new Retrofit.Builder().baseUrl(wanted).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(ApiService.class);base=wanted;}return service;}
 public static synchronized void reset(){service=null;base=null;}
}
