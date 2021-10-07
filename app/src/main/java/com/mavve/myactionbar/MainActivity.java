package com.mavve.myactionbar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toast.makeText(this,"onCreate körs",Toast.LENGTH_SHORT).show();

    }

    @Override
    protected void onResume() {
        Toast.makeText(this,"onResume körs",Toast.LENGTH_SHORT).show();
        super.onResume();
    }

    @Override
    protected void onPause() {
        Toast.makeText(this,"onPause körs",Toast.LENGTH_SHORT).show();
        super.onPause();
    }

    @Override
    protected void onStart() {
        Toast.makeText(this,"onStart körs",Toast.LENGTH_SHORT).show();
        super.onStart();
    }

    @Override
    protected void onDestroy() {
        Toast.makeText(this,"onDestroy körs",Toast.LENGTH_SHORT).show();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        Toast.makeText(this,"onStop körs",Toast.LENGTH_SHORT).show();
        super.onStop();
    }

    @Override
    protected void onRestart() {
        Toast.makeText(this,"onRestart körs",Toast.LENGTH_SHORT).show();

        super.onRestart();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
        //return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings){
            showSettingsActivity();
            //finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSettingsActivity() {
        Toast.makeText(this,"knappen funkar",Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, SecondActivity.class);
        startActivity(intent);
    }
}