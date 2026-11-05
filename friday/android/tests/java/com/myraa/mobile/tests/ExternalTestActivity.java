package com.myraa.mobile.tests;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public class ExternalTestActivity extends Activity {
  @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView label=new TextView(this);label.setText("Testing MYRAA Live while another app is open…");label.setPadding(20,50,20,20);setContentView(label);}
  @Override protected void onPause(){super.onPause();finish();}
}
