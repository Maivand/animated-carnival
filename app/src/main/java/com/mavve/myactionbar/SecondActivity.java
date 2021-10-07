package com.mavve.myactionbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class SecondActivity extends AppCompatActivity {

        ArrayList<String> things;
        ArrayAdapter<Person> adapter;
        ArrayList<Person> personArrayList;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_second);
        things = new ArrayList<String>();
        things.add("Namn 1");
        things.add("Namn 2");
        things.add("Namn 3");
        things.add("Namn 4");
        things.add("Namn 5");

        personArrayList = new ArrayList<Person>();

        Person p1 = new Person("Kalle",21);
        Person p2 = new Person("Erik",22);
        Person p3 = new Person("Jonas",32);

        personArrayList.add(p1);
        personArrayList.add(p2);
        personArrayList.add(p3);

        adapter = new ArrayAdapter<Person>(getApplicationContext(),
                android.R.layout.simple_list_item_1,personArrayList){
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view =super.getView(position,convertView,parent);

                TextView text1 = (TextView)view.findViewById(android.R.id.text1);

                text1.setText(personArrayList.get(position).getName()+", "+personArrayList.get(position).getAge());
                //text2.setText(personArrayList.get(position).getAge());
                return view;
            }
        };

        ListView listView =(ListView) findViewById(R.id.list_item);
        listView.setAdapter(adapter);

        Button button = (Button) findViewById(R.id.add_button);

        AdapterView.OnItemClickListener listListener = new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                Person person = (Person) adapterView.getAdapter().getItem(i);
                String name = person.getName();
                int age = person.getAge();
                Intent intent = new Intent(getBaseContext(),PersonActivity.class);
                intent.putExtra("name", name);
                intent.putExtra("age",age);
                startActivity(intent);

                Toast.makeText(getApplicationContext(),name,Toast.LENGTH_SHORT).show();
                Log.d("log",name);
            }
        };
        listView.setOnItemClickListener(listListener);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings_menu,menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.back_to_main){
            backToMain();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void backToMain() {
        Intent intent = new Intent(this,MainActivity.class);
        startActivity(intent);
    }

    public void addMethod(View view) {
        things.add("nytt");
        adapter.notifyDataSetChanged();
        Toast.makeText(this,"added to list",Toast.LENGTH_SHORT).show();

    }


}