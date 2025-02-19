package com.example.camera;

import static android.hardware.SensorManager.SENSOR_ORIENTATION;
import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PICK_FILE = 1001;
    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private String cameraId;
    private CameraManager cameraManager;
    private Button captureButton;
    private Button loadButton;
    private ImageReader imageReader;


    private String whiteBalance = "auto";
    private int iso = 800;
    private long exposureTime = 100000L;
    private TextView parametersTextView;

    private static final int REQUEST_STORAGE_PERMISSION_IMG = 101;
    private static final int REQUEST_STORAGE_PERMISSION = 102;
    private static final int REQUEST_CAMERA_PERMISSION = 102;

    private ImageReader rawImageReader;
    private String mRawSupportedCameraId = null;
    private ImageReader mRawImageReader = null;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Size previewSize;
    class CameraParams {
        int iso = 0;
        long exposureTime = 0;
        String whiteBalance = "AUTO";
        boolean paramsLoaded = false;
    }
    private final CameraParams captureParams = new CameraParams();
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }
    private CaptureResult lastCaptureResult; // 用于保存捕获结果
    private CameraCaptureSession mRawCaptureSession; // RAW专用会话
    private final Object mSessionLock = new Object(); // 会话操作锁
    private final Handler mainHandler = new Handler(Looper.getMainLooper());






    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        captureButton = findViewById(R.id.captureButton);
        loadButton = findViewById(R.id.loadButton);
        parametersTextView = findViewById(R.id.parametersTextView);


        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        // Check for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        }

        // Check for storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                        REQUEST_STORAGE_PERMISSION_IMG);
            }

        }

        textureView.setSurfaceTextureListener(surfaceTextureListener);
        captureButton.setOnClickListener(v -> capturePhoto());

        checkRawSupport();
        startBackgroundThread();

        loadButton.setOnClickListener(v -> {
            pickFile();
        });
    }



    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            }
        }
        else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void checkRawSupport() {
        try {
            mRawSupportedCameraId = null;

            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

                if (isRawReallySupported(characteristics)) {
                    mRawSupportedCameraId = cameraId;
                    Log.d(TAG, "Found RAW supported camera: " + cameraId);
                    break;
                }

            }
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(mRawSupportedCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null || !map.isOutputSupportedFor(ImageFormat.RAW_SENSOR)) {
                Log.e(TAG, "不支持RAW_SENSOR格式输出");
            }

            if (mRawSupportedCameraId != null) {
                Log.d(TAG, "Using RAW camera: " + mRawSupportedCameraId);

                tryInitCamera();
            } else {
                Log.e(TAG, "No RAW supported cameras found");
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private boolean isRawReallySupported(CameraCharacteristics characteristics) {
        int[] caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean hasRawCap = false;
        for (int cap : caps) {
            if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                hasRawCap = true;
                break;
            }
        }
        // 华为设备特殊检测
        if (Build.MANUFACTURER.equalsIgnoreCase("HUAWEI")) {
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return map.getOutputSizes(ImageFormat.RAW_SENSOR) != null;
        }

//        try {
//            Method flushHandler = CameraDevice.class
//                    .getMethod("flushBuffersAndDisconnect");
//            flushHandler.invoke(cameraDevice);
//        } catch (Exception e) {
//            Log.w(TAG, "Huawei flush failed", e);
//        }
//
//        mainHandler.postDelayed(() -> createPreviewSession(), 500);

        return hasRawCap;
    }

    private void tryInitCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && textureView.isAvailable()) {
//            openCamera(mRawSupportedCameraId);
        }
    }


    private void openCamera() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(mRawSupportedCameraId, stateCallback, null);

                Log.d(TAG, "Camera opened successfully");
            }else {
                Log.d(TAG, "Camera permission not granted");
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(mRawSupportedCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);

            previewSize = chooseOptimalSize(previewSizes, textureView.getWidth(), textureView.getHeight());


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private Size chooseOptimalSize(Size[] choices, int width, int height) {
        // 实现尺寸匹配逻辑（例如选择最接近TextureView宽高比的最大分辨率）
        // 这里简化示例直接选最大尺寸
        return Collections.max(Arrays.asList(choices),
                (a, b) -> Long.compare(a.getWidth() * a.getHeight(), b.getWidth() * b.getHeight()));
    }

    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
//            createCameraSession();
            createPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
            openCamera();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            openCamera();
        }
    };


    private void createPreviewSession() {
        if (Build.MANUFACTURER.equalsIgnoreCase("huawei")) {
            try {
                Method prepareSync = CameraDevice.class
                        .getMethod("prepareHardwareSync");
                prepareSync.invoke(cameraDevice);
            } catch (Exception e) {
                Log.w(TAG, "Huawei sync prepare failed", e);
            }
        }
        try {
            SurfaceTexture previewTexture = textureView.getSurfaceTexture();
            previewTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(previewTexture);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            cameraCaptureSession = session;
//                            updatePreviewWithParameters(previewSurface); // 应用参数到预览
                            CaptureRequest.Builder builder = null;
                            try {
                                builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            } catch (CameraAccessException e) {
                                throw new RuntimeException(e);
                            }
                            builder.addTarget(previewSurface);

                            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

                            try {
                                cameraCaptureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Preview session config failed");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Create preview session failed", e);
        }
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (SecurityException e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                    return;
                }

                readFileFromUri(uri);
//                createPreviewSession();
            }
        } else {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show();
        }
    }

    private void readFileFromUri(Uri uri) {

        whiteBalance = null;
        iso = 0;
        exposureTime = 0L;

        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, "Reading line: " + line);
                if (line.startsWith("white_balance=")) {
                    whiteBalance = line.split("=")[1].trim();
                } else if (line.startsWith("ISO=")) {
                    iso = Integer.parseInt(line.split("=")[1].trim());
                } else if (line.startsWith("exposure_time=")) {
                    String expStr = line.split("=")[1].trim().replace("L", "");
                    exposureTime = Long.parseLong(expStr);
                }
            }
//            reader.close();
            Log.d(TAG, "Loaded parameters: whiteBalance=" + whiteBalance + ", ISO=" + iso + ", exposureTime=" + exposureTime);

            String parametersText = "White Balance: " + whiteBalance + "\n"
                    + "ISO: " + iso + "\n"
                    + "Exposure Time: " + exposureTime + " ns";

//            runOnUiThread(() -> parametersTextView.setText(parametersText));
//            Toast.makeText(this, "read successfully", Toast.LENGTH_LONG).show();

            runOnUiThread(() -> {
                parametersTextView.setText(parametersText);
                captureParams.iso = iso;      // 存储到拍摄参数
                captureParams.exposureTime = exposureTime;
                captureParams.whiteBalance = whiteBalance;
                captureParams.paramsLoaded = true;

                Toast.makeText(this, "参数已缓存", Toast.LENGTH_SHORT).show();

                resetCameraSession();
            });


            } catch (SecurityException e) {
            Log.e(TAG, "permission denied", e);
            Toast.makeText(this, "文件访问权限丢失，请重新选择", Toast.LENGTH_LONG).show();
            resetParameters();
        } catch (IOException e) {
            Log.e(TAG, "IO error", e);
            Toast.makeText(this, "文件读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            resetParameters();
        } catch (Exception e) {
            Log.e(TAG, "unexcepted error", e);
            Toast.makeText(this, "配置加载失败", Toast.LENGTH_LONG).show();
            resetParameters();
        }

    }

    private void resetParameters() {
        whiteBalance = null;
        iso = 0;
        exposureTime = 0L;
        runOnUiThread(() -> parametersTextView.setText("参数未加载"));
    }

    private void resetCameraSession(){
        closeCamera();
        openCamera();
    }

    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }


    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            cameraId = mRawSupportedCameraId;
            if (cameraId != null) {
                openCamera();
                Log.d(TAG, "Camera opened");
            } else {
                Log.d(TAG, "Camera not found");
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };


private void capturePhoto() {
    if (cameraDevice == null || cameraCaptureSession == null) {
        return;
    }

    if (cameraId != null) {
        checkRawSupport();  // Check if RAW is supported again

        if (cameraId != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    captureRawImage();
                }
            }).start();
        } else {
            Toast.makeText(this, "RAW format not supported", Toast.LENGTH_SHORT).show();
        }
    }
}

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 华为专用硬件复位
        if (Build.MANUFACTURER.equalsIgnoreCase("huawei")) {
            try {
                Class<?> hwCamera = Class.forName("android.hardware.camera2.impl.HwCamera2APIManager");
                Method forceReset = hwCamera.getMethod("forceResetCamera");
                forceReset.invoke(null);
            } catch (Exception e) {
                Log.w(TAG, "Huawei force reset failed", e);
            }
        }

        // 三重保障释放机制
        releaseCameraResources();
        System.gc();
        Runtime.getRuntime().runFinalization();
    }

    private void releaseCameraResources() {
        synchronized (mSessionLock) {
            // 关闭所有会话
            closeSession(mRawCaptureSession);
            mRawCaptureSession = null;
//            closeSession(captureSession);

            // 释放ImageReader
            if (rawImageReader != null) {
                rawImageReader.setOnImageAvailableListener(null, null);
                rawImageReader.close();
                rawImageReader = null;
            }

            // 关闭相机设备
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        }
    }

    private void closeSession(CameraCaptureSession session) {
        if (session != null) {
            try {
                session.abortCaptures();
                session.close();
            } catch (IllegalStateException | CameraAccessException e) {
                Log.w(TAG, "Session close error", e);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (rawImageReader != null) {
            rawImageReader.close();
            rawImageReader = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void applyCaptureParameters(CaptureRequest.Builder builder) {



        if (captureParams.iso != 0) builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        if (captureParams.exposureTime != 0) {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
        }

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
    }


    private boolean initRawImageReader() {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            );

            Size[] rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            if (rawSizes == null || rawSizes.length == 0) return false;

            Size rawSize = rawSizes[0];
            if (rawImageReader != null) {
                rawImageReader.close();
            }

            rawImageReader = ImageReader.newInstance(
                    rawSize.getWidth(),
                    rawSize.getHeight(),
                    ImageFormat.RAW_SENSOR,
                    2
            );

            rawImageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireNextImage();
                    if (image == null) return;

                    // 立即记录必要信息（在关闭前）
                    Log.d(TAG, "RAW格式: " + image.getFormat());
                    Log.d(TAG, "时间戳: " + image.getTimestamp());
                    Log.d(TAG, "Planes数量: " + image.getPlanes().length);

                    // 数据校验
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    if (buffer.remaining() < 1024) {
                        Log.e(TAG, "数据量不足，可能损坏");
                        return;
                    }

                    // 移交保存职责
                    saveRawImage(image);
                }catch (IllegalStateException e) {
                    Log.e(TAG, "Image already closed", e);
                }finally {
                    if (image != null) image.close();
                }
            }, backgroundHandler);

            return true;
        } catch (CameraAccessException e) {
            Log.e(TAG, "Init RAW reader failed", e);
            return false;
        }
    }


    private void captureRawImage() {
        // 0. 确保 RAW ImageReader 已初始化
        if (cameraDevice == null || isDestroyed()) {
            Log.e(TAG, "Invalid state for capture");
            return;
        }

        if (rawImageReader == null) {
            if (!initRawImageReader()) { // 封装初始化逻辑
                Toast.makeText(this, "RAW初始化失败", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        try {

            if (mRawCaptureSession != null) {
                mRawCaptureSession.abortCaptures();
                mRawCaptureSession.close();
                mRawCaptureSession = null;
            }

            // 1. 创建专属 RAW 捕获会话（临时）
            List<Surface> rawOutputs = Collections.singletonList(rawImageReader.getSurface());
            cameraDevice.createCaptureSession(
                    rawOutputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                // 2. 构建 RAW 捕获请求
                                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE
                                );
                                builder.addTarget(rawImageReader.getSurface());

                                // 3. 应用当前参数（ISO/曝光等）
                                applyCaptureParameters(builder);

                                // 4. 执行单次捕获
                                session.capture(
                                        builder.build(),
                                        new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureCompleted(
                                                    @NonNull CameraCaptureSession session,
                                                    @NonNull CaptureRequest request,
                                                    @NonNull TotalCaptureResult result
                                            ) {
                                                // 5. 捕获完成后自动恢复预览
                                                synchronized (MainActivity.this) {
                                                    lastCaptureResult = result; // 同步写入
                                                }

                                                backgroundHandler.postDelayed(()->{
                                                    synchronized (mSessionLock) {
                                                        if (mRawCaptureSession != null) {
                                                            try {
                                                                mRawCaptureSession.abortCaptures();
                                                                mRawCaptureSession.close();
                                                            } catch (IllegalStateException e) {
                                                                Log.w(TAG, "Close RAW session error", e);
                                                            } catch (CameraAccessException e) {
                                                                throw new RuntimeException(e);
                                                            }
//                                                            mRawCaptureSession = null;
                                                            mRawCaptureSession.close();
                                                        }
                                                    }

                                                    mainHandler.postDelayed(() -> {
                                                        if (!isDestroyed() && cameraDevice != null) {
                                                            createPreviewSession();
                                                        }
                                                    }, Build.MANUFACTURER.equalsIgnoreCase("huawei") ? 300 : 200);

                                                },500);



//                                                runOnUiThread(() -> {
//                                                    Toast.makeText(MainActivity.this,
//                                                            "RAW Captured", Toast.LENGTH_SHORT).show();
//                                                    // 重启预览会话
//                                                    createPreviewSession();
//                                                });
                                            }
                                        },
                                        backgroundHandler
                                );
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "RAW capture failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "RAW session config failed");
                            // 失败时也恢复预览
                            createPreviewSession();
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Start RAW session failed", e);
            resetHuaweiCamera();
        }
    }

    private void saveRawImage(Image image) {
        Log.i(TAG, "Enter saveRawImage" );

        synchronized (this) {
            // 关键数据校验
            if (cameraId == null || lastCaptureResult == null || cameraManager == null) {
                Log.e(TAG, "Missing camera metadata - cameraId: " + cameraId
                        + " result: " + lastCaptureResult
                        + " manager: " + cameraManager);
//                image.close();
                return;
            }

            try {
                // 获取必要参数
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(mRawSupportedCameraId);

                File outputFile = new File(getExternalFilesDir(null), "sensor_raw_" + System.currentTimeMillis() + ".dng");

                // 创建DNG并写入
                try (DngCreator dng = new DngCreator(cc, lastCaptureResult);
                     FileOutputStream fos = new FileOutputStream(outputFile)) {

                    dng.writeImage(fos, image);
                    Log.i(TAG, "DNG saved: " + outputFile.getAbsolutePath());

                    runOnUiThread(() -> Toast.makeText(this,
                            "Saved: " + outputFile.getName(), Toast.LENGTH_SHORT).show());
                }
            }  catch (CameraAccessException | IOException | IllegalStateException e) {
                Log.e(TAG, "Save DNG failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Save failed: " + e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show());
            }finally {
                image.close();
            }
        }
    }

    private void resetHuaweiCamera() {
        try {
            Class<?> hwCam = Class.forName("com.huawei.camera2.HwCamera2Manager");
            Method reset = hwCam.getMethod("forceReset");
            reset.invoke(null);
        } catch (Exception e) {
            Log.w(TAG, "Huawei reset failed", e);
        }
    }


}