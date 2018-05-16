package tillmaro.hsa.de.servicetest;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.widget.Toast;

public class MyService extends Service {

    private VideoRecorder recorder;


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = showOldNotification("Crashmate", "Press Start to start recording");

        switch (intent.getAction()){
            case Constants.ACTION.START_SERVICE :
                break;
            case Constants.ACTION.START_RECORD :
                start_recording();
                break;
            case Constants.ACTION.STOP_RECORD :
                stop_recording();
                break;
            case Constants.ACTION.STOP_SERVICE :
                stopForeground(true);
                stopSelf();
                break;
        }

        return Service.START_STICKY;
    }

    @Override
    public void onCreate(){
        recorder = new VideoRecorder(this);
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

    private void start_recording(){
        //TODO: Funktion für Aufnahme der OBD-Daten einfügen und in eigenem Thread laufen lassen
        recorder.startRecordingVideo();
        Toast.makeText(this, "Started", Toast.LENGTH_SHORT).show();
    }

    private void stop_recording() {
        //TODO: Aufnahme OBD stoppen
        recorder.stopRecordingVideo();
        Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show();
    }
}
