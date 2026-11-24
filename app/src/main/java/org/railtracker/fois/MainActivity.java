package org.railtracker.fois;
import android.app.*;import android.os.*;import android.webkit.*;import android.widget.*;import android.graphics.Color;import android.view.*;
public class MainActivity extends Activity {
 WebView web;
 public void onCreate(Bundle b){super.onCreate(b); LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setBackgroundColor(Color.rgb(13,30,48));l.setPadding(16,48,16,24);
 TextView t=new TextView(this);t.setText("FOIS  /  RAIL TRACKER");t.setTextColor(Color.WHITE);t.setTextSize(24);l.addView(t);
 TextView s=new TextView(this);s.setText("Freight enquiry • Official portal • Manual verification");s.setTextColor(0xff9bd9d1);l.addView(s);
 Button refresh=new Button(this);refresh.setText("Open freight enquiry");l.addView(refresh);
 web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.setWebViewClient(new WebViewClient());l.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(l);
 refresh.setOnClickListener(v->web.loadUrl("https://www.fois.indianrail.gov.in/FOISWebPortal/"));web.loadUrl("https://www.fois.indianrail.gov.in/FOISWebPortal/");
 }
 @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
}