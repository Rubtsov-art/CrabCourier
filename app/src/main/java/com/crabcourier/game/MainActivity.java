package com.crabcourier.game;
import android.app.*; import android.os.*; import android.view.*;
public class MainActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(1024,1024);setContentView(new GameView(this));}
}
