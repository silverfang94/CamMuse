package com.app.silver_fang.cammuse;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URI;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class CamMuseActivity extends AppCompatActivity {

    private static final String TAG = "CamMuse";
    public static final String CAMERA_FRONT = "1";
    public static final String CAMERA_BACK = "0";
    private  static  final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    public static final int IMAGE_GALLERY_REQUEST = 20;
    private int mCaptureState = STATE_PREVIEW;
    private int mActualCenter = 0;
    private int mActualRight = 1;
    private int mActualLeft = 2;
    private TextureView mTextureView;
    private ImageView mCenterButton;
    private ImageView mRightButton;
    private ImageView mLeftButton;
    private ImageView mSwitchCameraButton;
    private ImageView mMicBackground;
    private ImageView mFolder;
    private CameraDevice mCameraDevice;
    private String mCameraId = CAMERA_FRONT;
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private Size mPreviewSize;
    private Size mVideoSize;
    private Size mImageSize;
    private ImageReader mImageReader;
    private MediaRecorder mMediaRecorder;
    private Chronometer mChronometer;
    private int mRotation;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private File mVideoFolder;
    private String mVideoFileName;
    private File mImageFolder;
    private String mImageFileName;
    private File mAudioFolder;
    private String mAudioFileName;
    private MediaRecorder mAudioRecorder;
    private boolean mRecording = false;
    private boolean mAudioRecording = false;
    private CameraCaptureSession mRecordCaptureSession;
    private CameraCaptureSession mPreviewCaptureSession;

    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0,270);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 90);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new
            ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    mBackgroundHandler.post(new ImageSaver(reader.acquireLatestImage()));
                }
            };
    private class ImageSaver implements Runnable {

        private final Image mImage;

        public ImageSaver(Image image) {
            mImage = image;
        }

        @Override
        public void run() {
            ByteBuffer byteBuffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);

            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(mImageFileName);
                fileOutputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();

                Intent mediaStoreUpdateIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaStoreUpdateIntent.setData(Uri.fromFile(new File(mImageFileName)));
                sendBroadcast(mediaStoreUpdateIntent);

                if(fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

        }
    }

    private CameraCaptureSession.CaptureCallback mPreviewCaptureCallback = new
            CameraCaptureSession.CaptureCallback() {

                private void process(CaptureResult captureResult) {
                    switch (mCaptureState) {
                        case STATE_PREVIEW:
                            break;
                        case STATE_WAIT_LOCK:
                            mCaptureState = STATE_PREVIEW;
                            Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                            //if(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                              //      afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                startStillCaptureRequest();
                            //}
                            break;
                    }
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);

                    process(result);
                }
            };

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            setupCamera(width,height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };
    private  CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            if(mRecording){
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                startRecord();
                mMediaRecorder.start();
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.setVisibility(View.VISIBLE);
                mChronometer.start();
            }
            else {
                startPreview();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;
        }
    };



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cam_muse);
        createVideoFolder();
        createImageFolder();
        createAudioFolder();

        mTextureView = (TextureView) findViewById(R.id.textureView);
        mCenterButton = (ImageView) findViewById(R.id.btn_center);
        mRightButton = (ImageView) findViewById(R.id.btn_right);
        mLeftButton = (ImageView) findViewById(R.id.btn_left);
        mSwitchCameraButton = (ImageView) findViewById(R.id.swith_camera);
        mChronometer = (Chronometer) findViewById(R.id.chronometer);
        mMicBackground = (ImageView) findViewById(R.id.mic_background);
        mFolder = (ImageView) findViewById(R.id.btn_folder);

        mRightButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!mRecording || !mAudioRecording) {
                    int mAux = mActualCenter;
                    mActualCenter = mActualRight;
                    mActualRight = mAux;

                    setButtonImage(mCenterButton, mActualCenter, true);
                    setButtonImage(mRightButton, mActualRight, false);
                }
            }
        });
        mLeftButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!mRecording || !mAudioRecording) {
                    int mAux = mActualCenter;
                    mActualCenter = mActualLeft;
                    mActualLeft = mAux;

                    setButtonImage(mCenterButton, mActualCenter, true);
                    setButtonImage(mLeftButton, mActualLeft, false);
                }
            }
        });
        mSwitchCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!mRecording || !mAudioRecording) {
                    switchCamera();
                }
            }
        });
        mCenterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                centerButtonImageOnClickMethod();
            }
        });
        mFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!mRecording || !mAudioRecording) {
                    Intent intent;
                    switch (mActualCenter) {
                        case 0: {
                            intent = new Intent(Intent.ACTION_PICK);
                            String pictureDirectoryPath = mImageFolder.getPath();
                            Uri date = Uri.parse(pictureDirectoryPath);
                            intent.setDataAndType(date,"image/jpg");
                            startActivityForResult(intent, IMAGE_GALLERY_REQUEST);
                        }
                        break;
                        case 1: {
                            intent = new Intent(Intent.ACTION_PICK);
                            String videoDirectoryPath = mVideoFolder.getPath();
                            Uri date = Uri.parse(videoDirectoryPath);
                            intent.setDataAndType(date,"video/mpeg");
                            startActivityForResult(intent, 21);
                        }
                        break;
                        case 2: {
                            /*
                            intent = new Intent(Intent.ACTION_PICK);
                            File audioDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                            String audioDirectoryPath = audioDirectory.getPath();
                            Uri date = Uri.parse(audioDirectoryPath);
                            intent.setDataAndType(date,"audio/3gp");
                            startActivityForResult(intent, 22);*/
                        }
                        break;
                    }
                }
            }
        });

    }
    @Override
    protected  void onResume(){
        super.onResume();

        startBackgroundThread();

        if(mTextureView.isAvailable()){
            setupCamera(mTextureView.getWidth(),mTextureView.getHeight());
            connectCamera();
        }
        else{
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode == REQUEST_CAMERA_PERMISSION_RESULT){
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED){
                Toast.makeText(getApplicationContext(),"Camera services must camera permission.",Toast.LENGTH_SHORT).show();
            }
            if(grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(),
                        "Camera services must audio permission.", Toast.LENGTH_SHORT).show();
            }
        }
        if(requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT){
            if(grantResults[0] == PackageManager.PERMISSION_GRANTED){
                mRecording = true;
                mCenterButton.setImageResource(R.drawable.btn_video_center);
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Toast.makeText(this,
                        "Permission successfully granted.",Toast.LENGTH_SHORT).cancel();
            }
            else {
                Toast.makeText(this,
                        "Camera services must write permission.",Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onPause(){
        closeCamera();
        stopBackgroundThread();

        super.onPause();
    }

    @Override
    public void  onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();

        if(hasFocus){
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    |   View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    |   View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    |   View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    |   View.SYSTEM_UI_FLAG_FULLSCREEN
                    |   View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }

    }
    private void  setButtonImage(ImageView imageView,int actualImage,boolean isCenter){
        if (isCenter){
            switch (actualImage){
                case 0:{
                    imageView.setImageResource(R.drawable.btn_photo_center);
                    mMicBackground.setVisibility(View.INVISIBLE);/*
                    stopBackgroundThread();
                    closeCamera();
                    startBackgroundThread();
                    setupCamera(mTextureView.getWidth(),mTextureView.getHeight());
                    connectCamera();*/
                }
                    break;
                case 1:{
                    imageView.setImageResource(R.drawable.btn_video_center);
                    mMicBackground.setVisibility(View.INVISIBLE);/*
                    stopBackgroundThread();
                    closeCamera();
                    startBackgroundThread();
                    setupCamera(mTextureView.getWidth(),mTextureView.getHeight());
                    connectCamera();*/
                }
                    break;
                case 2:{
                    imageView.setImageResource(R.drawable.btn_mic_center);
                    //stopBackgroundThread();
                    //closeCamera();
                    mMicBackground.setVisibility(View.VISIBLE);
                }
                    break;
            }
        }
        else{
            switch (actualImage){
                case 0:imageView.setImageResource(R.drawable.btn_photo_unselected);
                    break;
                case 1:imageView.setImageResource(R.drawable.btn_video_unselected);
                    break;
                case 2:imageView.setImageResource(R.drawable.btn_mic_unselected);
                    break;
            }
        }
    }
    private void centerButtonImageOnClickMethod(){
        switch (mActualCenter){
            case 0: createPhoto();
                break;
            case 1: recordVideo();
                break;
            case 2: recordAudio();
                break;
        }
    }
    private void closeCamera(){
        if(mCameraDevice != null){
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if(mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }
    private void startRecord() {
        try {
            if(mRecording) {
                setupMediaRecorder();
            }
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recordSurface = mMediaRecorder.getSurface();
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCaptureRequestBuilder.addTarget(recordSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            mRecordCaptureSession = session;
                            try {
                                mRecordCaptureSession.setRepeatingRequest(
                                        mCaptureRequestBuilder.build(), null, null
                                );
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.d(TAG, "onConfigureFailed: startRecord");
                        }
                    }, null);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private void startPreview() {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                        mPreviewCaptureSession = session;
                    try {
                        mPreviewCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                null,mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                }
            },null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }
    private void setupCamera(int width,int height){
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            //for(String cameraId : cameraManager.getCameraIdList()){
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(mCameraId);/*
                if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT){
                    continue;
                }*/
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                mRotation = DeviceRotation(cameraCharacteristics,deviceOrientation);
                boolean swapRotation = mRotation == 90 || mRotation == 270;
                int widthRotate = width;
                int heightRotate = height;
                if(swapRotation){
                    widthRotate = height;
                    heightRotate = width;
                }
                mPreviewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class),widthRotate,heightRotate);
                mVideoSize = chooseSize(map.getOutputSizes(SurfaceTexture.class),widthRotate,heightRotate);
                mImageSize = chooseSize(map.getOutputSizes(ImageFormat.JPEG), widthRotate, heightRotate);
                mImageReader = ImageReader.newInstance(mImageSize.getWidth(), mImageSize.getHeight(), ImageFormat.JPEG, 1);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

            return;
            //}
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private  void connectCamera(){
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED){
                    cameraManager.openCamera(mCameraId,mCameraDeviceStateCallback,mBackgroundHandler);
                }
                else {
                    if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)){
                        Toast.makeText(this,"Video app require access to camera",Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[] {Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},REQUEST_CAMERA_PERMISSION_RESULT);
                }
            }
            else {
                cameraManager.openCamera(mCameraId,mCameraDeviceStateCallback,mBackgroundHandler);

            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startBackgroundThread(){
        mBackgroundHandlerThread = new HandlerThread("CamMuse");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }
    private void stopBackgroundThread(){
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    private  static int DeviceRotation(CameraCharacteristics cameraCharacteristics,int deviceOrientation){
        int orientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        orientation = ORIENTATIONS.get(deviceOrientation);
        return (orientation + deviceOrientation + 360) % 360;
    };
    private  static  class  CompareSize implements Comparator<Size>{

        @Override
        public int compare(Size left, Size right) {
            return Long.signum((long)(left.getWidth() * left.getHeight()) /
                    (long) (right.getWidth() * right.getHeight()) );
        }
    }
    private static  Size chooseSize(Size[] sizeList,int width,int height){
        List<Size> listSize = new ArrayList<Size>();
        for (Size option: sizeList){
            if(option.getHeight() == option.getWidth() * height/width
                    && option.getWidth() >= width
                    && option.getHeight() >= height){
                listSize.add(option);
            }
        }
      if(listSize.size() >0){
            return Collections.min(listSize,new CompareSize());
      }
      else{
           return sizeList[0];
      }
    };
    private void switchCamera() {
        if (mCameraId.equals(CAMERA_FRONT)) {
            mCameraId = CAMERA_BACK;

        } else if (mCameraId.equals(CAMERA_BACK)) {
            mCameraId = CAMERA_FRONT;
        }
        stopBackgroundThread();
        closeCamera();
        startBackgroundThread();
        setupCamera(mTextureView.getWidth(),mTextureView.getHeight());
        connectCamera();
        }
    private void createPhoto(){
        checkStoragePermission();
        lockFocus();

    }
    private void recordVideo (){
        if(mRecording) {
            mChronometer.stop();
            mChronometer.setVisibility(View.INVISIBLE);
            mRecording = false;

            mCenterButton.setImageResource(R.drawable.btn_video_center);
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mMediaRecorder.release();


            Intent mediaStoreUpdateIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaStoreUpdateIntent.setData(Uri.fromFile(new File(mVideoFileName)));
            sendBroadcast(mediaStoreUpdateIntent);

            stopBackgroundThread();
            closeCamera();
            startBackgroundThread();
            setupCamera(mTextureView.getWidth(),mTextureView.getHeight());
            connectCamera();
        }
        else{
            mRecording = true;
            mCenterButton.setImageResource(R.drawable.btn_video_selected);
            checkStoragePermission();
        }
    }
    private void recordAudio () {
        if(!mAudioRecording){
            try {
                createAudioFileName();
            } catch (IOException e) {
                e.printStackTrace();
            }
            setUpAudioRecorder();
            mAudioRecording = true;
            mCenterButton.setImageResource(R.drawable.btn_mic_selected);
            try {
                mAudioRecorder.prepare();
                mAudioRecorder.start();
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.setVisibility(View.VISIBLE);
                mChronometer.start();
            } catch (IllegalStateException ise) {
                ise.getStackTrace();
            } catch (IOException ioe) {
                ioe.getStackTrace();
            };
            Toast.makeText(getApplicationContext(), "Recording started", Toast.LENGTH_LONG).show();
        }
        else{
            mAudioRecorder.stop();
            mAudioRecorder.release();
            mAudioRecorder = null;
            mChronometer.stop();
            mChronometer.setVisibility(View.INVISIBLE);
            mAudioRecording = false;
            mCenterButton.setImageResource(R.drawable.btn_mic_center);

            /*
            Intent mediaStoreUpdateIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaStoreUpdateIntent.setData(Uri.fromFile(new File(mVideoFileName)));
            sendBroadcast(mediaStoreUpdateIntent);
            */
            Toast.makeText(getApplicationContext(), "Record saved.", Toast.LENGTH_LONG).show();
        }
    }
    private void createVideoFolder() {
        File videoFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        mVideoFolder = new File(videoFile,"CamMuse");
        if(!mVideoFolder.exists()){
            mVideoFolder.mkdirs();
        }
    }
    private File createVideoFileName() throws IOException {
        String dateTime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String apend = "Video_" + dateTime + "_";
        File videoFile = File.createTempFile(apend,".mp4",mVideoFolder);
        mVideoFileName = videoFile.getAbsolutePath();
        return videoFile;
    }
    private void createImageFolder() {
        File imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mImageFolder = new File(imageFile, "CamMuse");
        if(!mImageFolder.exists()) {
            mImageFolder.mkdirs();
        }
    }
    private File createImageFileName() throws IOException {
        String datetime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "IMAGE_" + datetime + "_";
        File imageFile = File.createTempFile(prepend, ".jpg", mImageFolder);
        mImageFileName = imageFile.getAbsolutePath();
        return imageFile;
    }
    private void createAudioFolder() {
        File audioFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        mAudioFolder = new File(audioFile, "CamMuse");
        if(!mAudioFolder.exists()) {
            mAudioFolder.mkdirs();
        }
    }
    private File createAudioFileName() throws IOException {
        String datetime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "AUDIO_" + datetime + "_";
        File audioFile = File.createTempFile(prepend, ".3gp", mImageFolder);
        mAudioFileName = audioFile.getAbsolutePath();
        return audioFile;
    }
    private  void checkStoragePermission(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED){
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if(mRecording) {
                    startRecord();
                    mMediaRecorder.start();
                    mChronometer.setBase(SystemClock.elapsedRealtime());
                    mChronometer.setVisibility(View.VISIBLE);
                    mChronometer.start();
                }
            }
            else {
                if(shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)){
                    Toast.makeText(this,"Application need to save videos",Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT);
            }
        }
        else {
            try {
                createVideoFileName();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if(mRecording) {
                startRecord();
                mMediaRecorder.start();
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.setVisibility(View.VISIBLE);
                mChronometer.start();
            }
        }
    }
    private void setupMediaRecorder() throws IOException {
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(mVideoFileName);
        mMediaRecorder.setVideoEncodingBitRate(1000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.setOrientationHint(mRotation);
        mMediaRecorder.prepare();
    }
    private void startStillCaptureRequest() {
        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            mCaptureRequestBuilder.addTarget(mImageReader.getSurface());
            mCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mRotation);

            CameraCaptureSession.CaptureCallback stillCaptureCallback = new
                    CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber);

                            try {
                                createImageFileName();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    };

                mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), stillCaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Toast.makeText(getApplicationContext(), "Photo is saved.", Toast.LENGTH_SHORT).show();
    }
    private void lockFocus() {
        mCaptureState = STATE_WAIT_LOCK;
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), mPreviewCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void setUpAudioRecorder(){
        mAudioRecorder = new MediaRecorder();
        mAudioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mAudioRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mAudioRecorder.setAudioEncoder(MediaRecorder.OutputFormat.AMR_NB);
        mAudioRecorder.setOutputFile(mAudioFileName);
    }
}
