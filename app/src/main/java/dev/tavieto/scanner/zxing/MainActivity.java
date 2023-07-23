package dev.tavieto.scanner.zxing;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import com.google.zxing.client.android.R;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}