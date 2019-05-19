package tillmaro.hsa.de.servicetest;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;


public class MyService extends Service {

    private CircularRecorder circularRecorder;
    private HandlerThread thread;

    private static final String TAG = "MyService";


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showOldNotification("Crashmate", "Press Start to start recording");

        switch (intent.getAction()){
            case Constants.ACTION.START_SERVICE :
                break;
            case Constants.ACTION.START_RECORD :
                start_continuous_recording();
                break;
            case Constants.ACTION.STOP_RECORD :
                Log.d(TAG, "Got stop Intent");
                stop_continuous_recording();
                break;
            case Constants.ACTION.STOP_SERVICE :
                Log.d(TAG, "Got kill Intent");
                stopForeground(true);
                stopSelf();
                break;
        }

        return Service.START_STICKY;
    }

    @Override
    public void onCreate(){
        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        Log.i(TAG, "Rotation: " + display.getRotation());
        circularRecorder = new CircularRecorder(this, display.getRotation());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification showOldNotification(String title, String content){
        Intent notificationIntent = new Intent(this, Main.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Intent startRecordIntent = new Intent(this, MyService.class);
        startRecordIntent.setAction(Constants.ACTION.START_RECORD);
        PendingIntent pStartRecordIntent = PendingIntent.getService(this, 0, startRecordIntent, 0);

        Intent stopRecordIntent = new Intent(this, MyService.class);
        stopRecordIntent.setAction(Constants.ACTION.STOP_RECORD);
        PendingIntent pStopRecordIntent = PendingIntent.getService(this, 0, stopRecordIntent, 0);

        //Für zukünftige Verwendung zum Stoppen des Service
        Intent stopServiceIntent = new Intent(this, MyService.class);
        stopRecordIntent.setAction(Constants.ACTION.STOP_SERVICE);
        PendingIntent pStopServiceIntent = PendingIntent.getService(this, 0, stopServiceIntent, 0);

        //TODO: Actions toggeln für bessere Übersicht
        Notification notification = new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.camera)
                .setContentIntent(pendingIntent)
                .setTicker(getText(R.string.app_name))
                .addAction(R.drawable.start, "Start", pStartRecordIntent)
                .addAction(R.drawable.stop, "Stop", pStopRecordIntent)
                .build();

        startForeground(Constants.NOTIFICATION_ID.FOREGROUND_SERVICE, notification);

        return notification;
    }

    private void start_continuous_recording(){
        thread = new HandlerThread("CR") {
            public void run() {
                Looper.prepare();
                circularRecorder.startRecord(getCrashmateFilePath() + "/continued.mp4");
                while(!Thread.currentThread().isInterrupted()){
                    try {
                        sleep(100);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Stopping thread");
                        circularRecorder.clickCapture(null);
                        circularRecorder.onPause();
                        thread.quit();
                    }
                }
            }
        };
        thread.start();
    }

    private String getCrashmateFilePath(){
        File crashmateFolder = Environment.getExternalStoragePublicDirectory("Crashmate");
        if(!crashmateFolder.exists()){
            crashmateFolder.mkdir();
        }
        return crashmateFolder.getAbsolutePath();
    }

    private void stop_continuous_recording(){
        thread.interrupt();
    }


}
