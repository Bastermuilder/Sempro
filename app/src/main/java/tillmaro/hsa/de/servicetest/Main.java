package tillmaro.hsa.de.servicetest;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

public class Main extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        checkDrawOverlayPermission();

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

    /** code to post/handler request for permission */
    public final static int REQUEST_CODE = 32985;

    public void checkDrawOverlayPermission() {
        /** check if we already  have permission to draw over other apps */
        if (!Settings.canDrawOverlays(this)) {
            /** if not construct intent to request permission */
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            /** request permission via start activity for result */
            startActivityForResult(intent, REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,  Intent data) {
        /** check if received result code
         is equal our requested code for draw permission  */
        if (requestCode == REQUEST_CODE) {
       /** if so check once again if we have permission */
            if (Settings.canDrawOverlays(this)) {
                // continue here - permission was granted
            }
        }
    }
}
