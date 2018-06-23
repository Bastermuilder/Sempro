package tillmaro.hsa.de.servicetest;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import tillmaro.hsa.de.servicetest.gles.EglCore;
import tillmaro.hsa.de.servicetest.gles.FullFrameRect;
import tillmaro.hsa.de.servicetest.gles.Texture2dProgram;
import tillmaro.hsa.de.servicetest.gles.WindowSurface;



public class CircularRecorder implements SurfaceTexture.OnFrameAvailableListener, SurfaceHolder.Callback {

    private static final String TAG = "CircularRecorder";

    private static final int VIDEO_WIDTH = 1080;  // dimensions for 720p video 720 1280
    private static final int VIDEO_HEIGHT = 1920;
    private static final int DESIRED_PREVIEW_FPS = 15;

    private int desired_video_length = 15;

    private EglCore mEglCore;
    private WindowSurface mDisplaySurface;
    private SurfaceTexture mCameraTexture;  // receives the output from the camera preview
    private FullFrameRect mFullFrameBlit;
    private final float[] mTmpMatrix = new float[16];
    private int mTextureId;
    private int mFrameNum;

    private int mCameraPreviewThousandFps;
    private CameraCaptureSession mPreviewSession;
    private AutoFitTextureView mTextureView;
    private Handler mBackgroundHandler;
    private CaptureRequest.Builder mPreviewBuilder;
    private WindowManager windowManager;

    private SurfaceView mSurfaceView;

    private File mOutputFile;
    private CircularEncoder mCircEncoder;
    private WindowSurface mEncoderSurface;
    private boolean mFileSaveInProgress;

    private MainHandler mHandler;
    private float mSecondsOfVideo;

    private Service service;

    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private CameraDevice mCameraDevice;
    private Integer mSensorOrientation;
    private Size mVideoSize;

    public CircularRecorder(Service service){
        mHandler = new MainHandler(this);

        this.service = service;

        windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);

        mSurfaceView = new SurfaceView(service);
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.START | Gravity.TOP;
        windowManager.addView(mSurfaceView, layoutParams);
        SurfaceHolder mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
    }

    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

    };

    private static class MainHandler extends Handler implements CircularEncoder.Callback {
        static final int MSG_FRAME_AVAILABLE = 1;
        static final int MSG_FILE_SAVE_COMPLETE = 2;
        static final int MSG_BUFFER_STATUS = 3;

        private WeakReference<CircularRecorder> mWeakActivity;

        MainHandler(CircularRecorder recorder) {
            mWeakActivity = new WeakReference<>(recorder);
        }

        // CircularEncoder.Callback, called on encoder thread
        @Override
        public void fileSaveComplete(int status) {
            sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null));
        }

        // CircularEncoder.Callback, called on encoder thread
        @Override
        public void bufferStatus(long totalTimeMsec) {
            sendMessage(obtainMessage(MSG_BUFFER_STATUS,
                    (int) (totalTimeMsec >> 32), (int) totalTimeMsec));
        }


        @Override
        public void handleMessage(Message msg) {
            CircularRecorder recorder = mWeakActivity.get();
            if (recorder == null) {
                Log.d(TAG, "Got message for dead activity");
                return;
            }

            switch (msg.what) {
                case MSG_FRAME_AVAILABLE: {
                    recorder.drawFrame();
                    break;
                }
                case MSG_FILE_SAVE_COMPLETE: {
                    recorder.fileSaveComplete(msg.arg1);
                    break;
                }
                case MSG_BUFFER_STATUS: {
                    long duration = (((long) msg.arg1) << 32) |
                            (((long) msg.arg2) & 0xffffffffL);
                    recorder.updateBufferStatus(duration);
                    break;
                }
                default:
                    throw new RuntimeException("Unknown message " + msg.what);
            }
        }
    }

    private void updateBufferStatus(long durationUsec) {
        mSecondsOfVideo = durationUsec / 1000000.0f;
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated holder=" + holder);

        // Set up everything that requires an EGL context.
        //
        // We had to wait until we had a surface because you can't make an EGL context current
        // without one, and creating a temporary 1x1 pbuffer is a waste of time.
        //
        // The display surface that we use for the SurfaceView, and the encoder surface we
        // use for video, use the same EGL context.
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        mDisplaySurface = new WindowSurface(mEglCore, holder.getSurface(), false);
        mDisplaySurface.makeCurrent();

        mFullFrameBlit = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
        mTextureId = mFullFrameBlit.createTextureObject();
        mCameraTexture = new SurfaceTexture(mTextureId);
        mCameraTexture.setOnFrameAvailableListener(this);
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed holder=" + holder);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mHandler.sendEmptyMessage(MainHandler.MSG_FRAME_AVAILABLE);
    }

    public void startRecord(String file_path){
        mOutputFile = new File(file_path);
        openCamera(VIDEO_WIDTH, VIDEO_HEIGHT);
    }

    public void stopRecord(){
        Log.d(TAG, "capture");
        if (mFileSaveInProgress) {
            Log.w(TAG, "HEY: file save is already in progress");
            return;
        }
        closeCamera();

        mFileSaveInProgress = true;
        mCircEncoder.saveVideo(mOutputFile);
    }

    private void startPreview() {
        try {
            closePreviewSession();
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Surface previewSurface = new Surface(mCameraTexture);
            mPreviewBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mPreviewSession = session;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "Configure CameraCaptureSession failed");
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        try {
            mCircEncoder = new CircularEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, 10000000,
                    DESIRED_PREVIEW_FPS, desired_video_length, mHandler);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mEncoderSurface = new WindowSurface(mEglCore, mCircEncoder.getInputSurface(), true);
    }

    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    private void fileSaveComplete(int status) {
        Log.d(TAG, "fileSaveComplete " + status);

        if (!mFileSaveInProgress) {
            throw new RuntimeException("WEIRD: got fileSaveComplete when not in progress");
        }
        mFileSaveInProgress = false;

    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            closePreviewSession();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void openCamera(int width, int height) {
        CameraManager manager = (CameraManager) service.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "tryAcquire");
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            String cameraId = manager.getCameraIdList()[0];

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }
            //mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));

            //int orientation = service.getResources().getConfiguration().orientation;
            if (ActivityCompat.checkSelfPermission(service, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(service,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {

                //manager.openCamera(cameraId, mStateCallback, null);
                HandlerThread handlerThread = new HandlerThread("CameraHandler");
                handlerThread.start();
                Handler handler = new Handler(handlerThread.getLooper());
                manager.openCamera(cameraId, mStateCallback, handler);
                //handler.notifyAll();
                if (mCameraDevice != null){
                    handlerThread.quitSafely();
                    try{
                        handlerThread.join();
                    }catch (InterruptedException e){
                        e.printStackTrace();
                    }
                }
            }

        } catch (CameraAccessException e) {
            Toast.makeText(service, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 16 / 9 && size.getWidth() <= 1920) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    private void drawFrame() {
        //Log.d(TAG, "drawFrame");
        if (mEglCore == null) {
            Log.d(TAG, "Skipping drawFrame after shutdown");
            return;
        }

        // Latch the next frame from the camera.
        mCameraTexture.updateTexImage();
        mCameraTexture.getTransformMatrix(mTmpMatrix);


        // Send it to the video encoder.
        if (!mFileSaveInProgress) {
            mEncoderSurface.makeCurrent();
            GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
            mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
            mCircEncoder.frameAvailableSoon();
            mEncoderSurface.setPresentationTime(mCameraTexture.getTimestamp());
            mEncoderSurface.swapBuffers();
        }

        mFrameNum++;
    }




}
