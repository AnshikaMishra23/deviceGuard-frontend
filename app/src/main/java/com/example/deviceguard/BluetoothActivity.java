package com.example.deviceguard;
import android.Manifest;import android.bluetooth.*;import android.content.*;import android.os.Bundle;import android.view.View;import android.widget.*;import androidx.appcompat.app.AppCompatActivity;import androidx.core.content.ContextCompat;import java.util.*;
public class BluetoothActivity extends AppCompatActivity{
 private BluetoothAdapter adapter;private LinearLayout list;private final Set<String> addresses=new HashSet<>();
 private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(BluetoothDevice.ACTION_FOUND.equals(i.getAction())){BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null&&addresses.add(d.getAddress())){TextView t=new TextView(BluetoothActivity.this);String n=d.getName();t.setText((n==null?"Unknown device":n)+"\n"+d.getAddress());t.setPadding(12,12,12,12);list.addView(t);}}}};
 @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_bluetooth);list=findViewById(R.id.bluetoothList);adapter=BluetoothAdapter.getDefaultAdapter();findViewById(R.id.scan).setOnClickListener(v->scan());}
 private void scan(){if(adapter==null){Toast.makeText(this,"Bluetooth not supported",Toast.LENGTH_SHORT).show();return;}if(android.os.Build.VERSION.SDK_INT>=31){if(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)!=getPackageManager().PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},44);return;}}else if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=getPackageManager().PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},44);return;}if(!adapter.isEnabled()){startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}list.removeAllViews();addresses.clear();try{unregisterReceiver(receiver);}catch(Exception ignored){}IntentFilter f=new IntentFilter(BluetoothDevice.ACTION_FOUND);if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);else registerReceiver(receiver,f);adapter.cancelDiscovery();adapter.startDiscovery();Toast.makeText(this,"Scanning for nearby Bluetooth devices…",Toast.LENGTH_SHORT).show();}
 @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==44){boolean ok=false;for(int g:grantResults)if(g==getPackageManager().PERMISSION_GRANTED)ok=true;if(ok)scan();else Toast.makeText(this,"Bluetooth permission denied",Toast.LENGTH_SHORT).show();}}
 @Override
 protected void onDestroy() {

  try {
   unregisterReceiver(receiver);
  } catch (Exception ignored) {
  }

  if (adapter != null) {

   if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {

    if (androidx.core.app.ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.BLUETOOTH_SCAN
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {

     if (adapter.isDiscovering()) {
      adapter.cancelDiscovery();
     }
    }

   } else {

    if (adapter.isDiscovering()) {
     adapter.cancelDiscovery();
    }
   }
  }

  super.onDestroy();
 }
}
