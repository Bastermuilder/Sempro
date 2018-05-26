package tillmaro.hsa.de.servicetest;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

public class Main extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);



        Context context = getBaseContext();

        Intent i = new Intent(context, MyService.class);
        i.putExtra("KEY1", "Value to be used by the service");
        i.setAction(Constants.ACTION.START_SERVICE);
        context.startService(i);

    }

    @Override
    protected void onDestroy(){
        Context context = getBaseContext();

        Intent i = new Intent(context, MyService.class);
        i.setAction(Constants.ACTION.STOP_SERVICE);
        context.startService(i);
        super.onDestroy();
    }
}
