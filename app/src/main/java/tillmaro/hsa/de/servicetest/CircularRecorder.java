/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tillmaro.hsa.de.servicetest;

import android.app.Service;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

import tillmaro.hsa.de.servicetest.encoder.CircularEncoder;
import tillmaro.hsa.de.servicetest.gles.EglCore;
import tillmaro.hsa.de.servicetest.gles.FullFrameRect;
import tillmaro.hsa.de.servicetest.gles.Texture2dProgram;
import tillmaro.hsa.de.servicetest.gles.WindowSurface;

/**
 * Demonstrates capturing video into a ring buffer.  When the "capture" button is clicked,
 * the buffered video is saved.
 * <p>
 * Capturing and storing raw frames would be slow and require lots of memory.  Instead, we
 * feed the frames into the video encoder and buffer the output.
 * <p>
 * Whenever we receive a new frame from the camera, our SurfaceTexture callback gets
 * notified.  That can happen on an arbitrary thread, so we use it to send a message
 * through our Handler.  That causes us to render the new frame to the display and to
 * our video encoder.
 */
public class CircularRecorder implements SurfaceTexture.OnFrameAvailableListener, SurfaceHolder.Callback {
    private static final String TAG = "CRT";

    private static final int VIDEO_WIDTH = 1920;  // dimensions for 720p video
    private static final int VIDEO_HEIGHT = 1080;
    private static final int DESIRED_PREVIEW_FPS = 15;

    private EglCore mEglCore;
    private SurfaceTexture mCameraTexture;  // receives the output from the camera preview
    private FullFrameRect mFullFrameBlit;
    private final float[] mTmpMatrix = new float[16];
    private int mTextureId;
    private int rotation;

    private Camera mCamera;

    private File mOutputFile;
    private CircularEncoder mCircEncoder;
    private WindowSurface mEncoderSurface;
    private boolean mFileSaveInProgress;

    private MainHandler mHandler;

    /**
     * Custom message handler for main UI thread.
     * <p>
     * Used to handle camera preview "frame available" notifications, and implement the
     * blinking "recording" text.  Receives callback messages from the encoder thread.
     */
    private static class MainHandler extends Handler implements CircularEncoder.Callback {
        static final int MSG_FRAME_AVAILABLE = 1;
        static final int MSG_FILE_SAVE_COMPLETE = 2;
        static final int MSG_BUFFER_STATUS = 3;

        private WeakReference<CircularRecorder> mWeakActivity;

        public MainHandler(CircularRecorder activity) {
            mWeakActivity = new WeakReference<>(activity);
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
            CircularRecorder recorderService = mWeakActivity.get();
            if (recorderService == null) {
                Log.d(TAG, "Got message for dead activity");
                return;
            }

            switch (msg.what) {
                case MSG_FRAME_AVAILABLE: {
                    recorderService.drawFrame();
                    break;
                }
                case MSG_FILE_SAVE_COMPLETE: {
                    recorderService.fileSaveComplete(msg.arg1);
                    break;
                }
                case MSG_BUFFER_STATUS: {
                    break;
                }
                default:
                    throw new RuntimeException("Unknown message " + msg.what);
            }
        }
    }

    public CircularRecorder(Service service, int rotation){

        WindowManager windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);

        SurfaceView mSurfaceView = new SurfaceView(service);
        mHandler = new MainHandler(this);

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.START | Gravity.TOP;
        windowManager.addView(mSurfaceView, layoutParams);
        SurfaceHolder mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);

        mOutputFile = new File(getGrafikaFilePath(), "/continuous-capture.mp4");
        this.rotation = rotation;
    }

    private String getGrafikaFilePath(){
        File crashmateFolder = Environment.getExternalStoragePublicDirectory("Grafika");
        if(!crashmateFolder.exists()){
            crashmateFolder.mkdir();
        }
        return crashmateFolder.getAbsolutePath();
    }

    public void startRecord(String file_path){
        mOutputFile = new File(file_path);
        openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, 15);
        startCamera();
    }

    protected void onPause() {

        releaseCamera();

        if (mCircEncoder != null) {
            mCircEncoder.shutdown();
            mCircEncoder = null;
        }
        if (mCameraTexture != null) {
            mCameraTexture.release();
            mCameraTexture = null;
        }
        if (mFullFrameBlit != null) {
            mFullFrameBlit.release(false);
            mFullFrameBlit = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
        Log.d(TAG, "onPause() done");
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewFps to the expected frame rate (which might actually be variable).
     */
    private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Try to set the frame rate to a constant value.
        int mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);

        mCamera.setParameters(parms);

        Camera.Size cameraPreviewSize = parms.getPreviewSize();
        String previewFacts = cameraPreviewSize.width + "x" + cameraPreviewSize.height +
                " @" + (mCameraPreviewThousandFps / 1000.0f) + "fps";
        Log.i(TAG, "Camera config: " + previewFacts);

        if(rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            mCamera.setDisplayOrientation(90);
        } else if (rotation == Surface.ROTATION_90) {
            mCamera.setDisplayOrientation(0);
        } else {
            mCamera.setDisplayOrientation(180);
        }

    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    /**
     * Handles onClick for "capture" button.
     */
    public void clickCapture(@SuppressWarnings("unused") View unused) {
        Log.d(TAG, "capture");
        if (mFileSaveInProgress) {
            Log.w(TAG, "HEY: file save is already in progress");
            return;
        }

        mFileSaveInProgress = true;
        mCircEncoder.saveVideo(mOutputFile);
    }

    /**
     * The file save has completed.  We can resume recording.
     */
    private void fileSaveComplete(int status) {
        Log.d(TAG, "fileSaveComplete " + status);
        if (!mFileSaveInProgress) {
            throw new RuntimeException("WEIRD: got fileSaveCmplete when not in progress");
        }
        mFileSaveInProgress = false;

        //TODO: Maybe show toast with success
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
        WindowSurface mDisplaySurface = new WindowSurface(mEglCore, holder.getSurface(), false);
        mDisplaySurface.makeCurrent();

        mFullFrameBlit = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
        mTextureId = mFullFrameBlit.createTextureObject();
        mCameraTexture = new SurfaceTexture(mTextureId);
        mCameraTexture.setOnFrameAvailableListener(this);

        startPreview();
    }

    private void startCamera(){
        if (mCamera != null) {
            Log.d(TAG, "starting camera preview");
            try {
                mCamera.setPreviewTexture(mCameraTexture);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            mCamera.startPreview();
        }
    }

    private void startPreview() {
        startCamera();

        // TODO: adjust bit rate based on frame rate?
        // TODO: adjust video width/height based on what we're getting from the camera preview?
        //       (can we guarantee that camera preview size is compatible with AVC video encoder?)

        try {

            Log.d(TAG, "Rotation: " + rotation);
            if(rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
                mCircEncoder = new CircularEncoder(VIDEO_HEIGHT, VIDEO_WIDTH, 6000000,
                        DESIRED_PREVIEW_FPS, 20, mHandler);
            } else {
                mCircEncoder = new CircularEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, 6000000,
                        DESIRED_PREVIEW_FPS, 20, mHandler);
            }
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mEncoderSurface = new WindowSurface(mEglCore, mCircEncoder.getInputSurface(), true);
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

    @Override   // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
//        Log.d(TAG, "frame available");
        mHandler.sendEmptyMessage(MainHandler.MSG_FRAME_AVAILABLE);
    }

    /**
     * Draws a frame onto the SurfaceView and the encoder surface.
     * <p>
     * This will be called whenever we get a new preview frame from the camera.  This runs
     * on the UI thread, which ordinarily isn't a great idea -- you really want heavy work
     * to be on a different thread -- but we're really just throwing a few things at the GPU.
     * The upside is that we don't have to worry about managing state changes between threads.
     * <p>
     * If there was a pending frame available notification when we shut down, we might get
     * here after onPause().
     */
    private void drawFrame() {
        if (mEglCore == null) {
            Log.d(TAG, "Skipping drawFrame after shutdown");
            return;
        }

        // Latch the next frame from the camera.
//        mDisplaySurface.makeCurrent();
        mCameraTexture.updateTexImage();
        mCameraTexture.getTransformMatrix(mTmpMatrix);

        // Send it to the video encoder.
        if (!mFileSaveInProgress) {
            mEncoderSurface.makeCurrent();

            if(rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180){
                GLES20.glViewport(0, 0, VIDEO_HEIGHT, VIDEO_WIDTH);
            } else {
                GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
            }
            mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
            mCircEncoder.frameAvailableSoon();
            mEncoderSurface.setPresentationTime(mCameraTexture.getTimestamp());
            mEncoderSurface.swapBuffers();
        }
    }
}
