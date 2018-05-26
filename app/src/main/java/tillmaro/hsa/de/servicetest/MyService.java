package tillmaro.hsa.de.servicetest;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.nfc.Tag;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import processing.ffmpeg.videokit.AsyncCommandExecutor;
import processing.ffmpeg.videokit.Command;
import processing.ffmpeg.videokit.LogLevel;
import processing.ffmpeg.videokit.VideoKit;
import processing.ffmpeg.videokit.VideoProcessingResult;

public class MyService extends Service {

    private VideoRecorder recorder;
    private HandlerThread thread;
    private int loop_count = 0;

    private static final String TAG = "MyService";


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
                Log.d(TAG, "Got stop Intent");
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

        thread = new HandlerThread("cutter") {
            public void run() {
                Looper.prepare();
                while(!Thread.currentThread().isInterrupted()){
                    try {
                        recorder.startRecordingVideo(getVideoFilePath(Integer.toString(loop_count % 3)));
                        sleep(5000);
                        recorder.stopRecordingVideo();
                        loop_count += 1;
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Stopping thread");
                        quit();
                    }
                }
            }
        };
        thread.start();

        Toast.makeText(this, "Started", Toast.LENGTH_SHORT).show();
    }

    private void stop_recording() {
        //TODO: Aufnahme OBD stoppen
        thread.interrupt();

        final File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        String mpath = dir.getAbsolutePath() + "/";

        switch (loop_count){
            case 0:
                if(loop_count == 0)
                    break;
                concatenate(mpath + "0.mp4", mpath + "1.mp4", mpath + "final.mp4");
                break;
            case 1:
                concatenate(mpath + "1.mp4", mpath + "0.mp4", mpath + "final.mp4");
                break;
            case 2:
                concatenate(mpath + "2.mp4", mpath + "1.mp4", mpath + "final.mp4");
                break;
        }

        Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show();
    }

    private String getVideoFilePath(String number) {
        final File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + number + ".mp4";
    }

    /**
     * Concatenates two videos
     * @param inputFile1 First video file path
     * @param inputFile2 Second video file path
     * @param outputFile Output file path
     */
    public static void concatenate(String inputFile1, String inputFile2, String outputFile) {
        Log.d(TAG, "Concatenating " + inputFile1 + " and " + inputFile2 + " to " + outputFile);
        String list = generateList(new String[] {inputFile1, inputFile2});

        //TODO: Command richtig hinbekommen
        VideoKit vk = new VideoKit();
        vk.setLogLevel(LogLevel.FULL);
        //Command command = vk.createCommand().customCommand("ffmpeg -f concat -i" + list + "-c copy" + outputFile).build();
        Command command = vk.createCommand()
                .overwriteOutput()
                .inputPath(inputFile1)
                .inputPath(inputFile2)
                .outputPath(outputFile)
                .customCommand("-f concat -c copy")
                .copyVideoCodec()
                .experimentalFlag()
                .build();

        command.execute();

        Log.d(TAG, "Done building");
        /*vk.run(new String[] {
                "ffmpeg",
                "-f",
                "concat",
                "-i",
                list,
                "-c",
                "copy",
                outputFile
        });*/
    }

    /**
     * Generate an ffmpeg file list
     * @param inputs Input files for ffmpeg
     * @return File path
     */
    private static String generateList(String[] inputs) {
        File list;
        Writer writer = null;
        try {
            list = File.createTempFile("ffmpeg-list", ".txt");
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(list)));
            for (String input: inputs) {
                writer.write("file '" + input + "'\n");
                Log.d(TAG, "Writing to list file: file '" + input + "'");
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "/";
        } finally {
            try {
                if (writer != null)
                    writer.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        Log.d(TAG, "Wrote list file to " + list.getAbsolutePath());
        return list.getAbsolutePath();
    }
}
