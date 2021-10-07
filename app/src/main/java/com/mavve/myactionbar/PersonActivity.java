package com.mavve.myactionbar;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public class PersonActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_person);

        TextView nameView = (TextView) findViewById(R.id.nameTextView);
        TextView ageView = (TextView) findViewById(R.id.ageTextView);
        Intent intent = getIntent();

        nameView.setText(intent.getStringExtra("name"));
        ageView.setText(String.valueOf(intent.getIntExtra("age",0)));

    }
}