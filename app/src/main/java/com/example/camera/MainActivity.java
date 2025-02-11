package com.example.camera;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private String cameraId;
    private CameraManager cameraManager;
    private Button captureButton;
    private Button loadButton;


    private String whiteBalance = "auto";
    private int iso = 100;
    private long exposureTime = 100000L;
    private TextView parametersTextView;

    private static final int REQUEST_STORAGE_PERMISSION = 1001;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        captureButton = findViewById(R.id.captureButton);
        loadButton = findViewById(R.id.loadButton);
        parametersTextView = findViewById(R.id.parametersTextView); // Initialize the TextView


        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        // Check for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 100);
        }

        // Check for storage permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // 请求权限
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }

        textureView.setSurfaceTextureListener(surfaceTextureListener);

        captureButton.setOnClickListener(v -> capturePhoto());

        // set listener for the load button
        loadButton.setOnClickListener(v -> {
            readCameraParameters();
            Toast.makeText(MainActivity.this, "Camera Parameters Loaded", Toast.LENGTH_SHORT).show();
        });

    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
                // 继续操作文件读取
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void readCameraParameters() {
        try {
            // Open the file and read parameters
//            FileInputStream fis = openFileInput("camera_parameters.txt");
//            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            // external storage
            File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File cameraParamsFile = new File(downloadFolder, "camera_parameters.txt");

            // open and read
            FileInputStream fis = new FileInputStream(cameraParamsFile);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));

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
            reader.close();

            Log.d(TAG, "Loaded parameters: whiteBalance=" + whiteBalance + ", ISO=" + iso + ", exposureTime=" + exposureTime);

            String parametersText = "White Balance: " + whiteBalance + "\n"
                    + "ISO: " + iso + "\n"
                    + "Exposure Time: " + exposureTime + " ns";
            parametersTextView.setText(parametersText);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    parametersTextView.setText(parametersText);
                }
            });

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error reading camera parameters: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            cameraId = getBackCameraId();
            if (cameraId != null) {
                openCamera(cameraId);
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

    private void openCamera(String cameraId) {
        try {

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, stateCallback, null);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
//            setupImageReader();
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
        }
    };

    private void startPreview() {
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                Surface surface = new Surface(surfaceTexture);
                captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                captureRequestBuilder.addTarget(surface);

                cameraDevice.createCaptureSession(Collections.singletonList(surface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                if (cameraDevice != null) {
                                    cameraCaptureSession = session;
                                    try {
                                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                                    } catch (CameraAccessException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {}
                        }, null);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private void capturePhoto() {
        if (cameraDevice == null || cameraCaptureSession == null) {
            return; // If camera is not initialized or session is not configured, do nothing
        }

        try {
            // Create CaptureRequest for still capture
            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // Set the surface where the photo will be saved to (TextureView's Surface)
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            Surface surface = new Surface(surfaceTexture);
            captureBuilder.addTarget(surface);


            // Add parameters for capture (flash, exposure, etc.)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);

            if (whiteBalance.equalsIgnoreCase("auto")) {
                captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            }

            CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    // Handle capture completion (e.g., display success or save the image)
                    Toast.makeText(MainActivity.this, "Capture Completed", Toast.LENGTH_SHORT).show();

//                    saveImageToGallery();
//                    saveImageToDownloadFolder();
                    saveImageWithExifData();


                    startPreview();  // Restart preview after capture
                }
            };

            // Capture the photo
            cameraCaptureSession.capture(captureBuilder.build(), captureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

//    private void saveImageToGallery() {
//        try {
//            Bitmap bitmap = textureView.getBitmap();
//            if (bitmap == null) {
//                Toast.makeText(this, "Failed to capture image from TextureView", Toast.LENGTH_SHORT).show();
//                return;
//            }
//            ContentValues values = new ContentValues();
//            values.put(MediaStore.Images.Media.TITLE, "Camera Capture");
//            values.put(MediaStore.Images.Media.DISPLAY_NAME, "camera_capture_" + System.currentTimeMillis() + ".jpg");
//            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
//            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
//
//            // Insert the image into MediaStore
//            ContentResolver resolver = getContentResolver();
//            Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
//
//            try (OutputStream outStream = resolver.openOutputStream(imageUri)) {
//                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream);
//                outStream.flush();
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//            Toast.makeText(this, "Error saving image to gallery: " + e.getMessage(), Toast.LENGTH_SHORT).show();
//        }
//    }


    private void saveImageToDownloadFolder() {
        // Save the image to the Downloads folder
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String imageFileName = "camera_capture_" + System.currentTimeMillis() + ".jpg";
        File imageFile = new File(downloadFolder, imageFileName);

        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            Bitmap bitmap = textureView.getBitmap();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            Toast.makeText(MainActivity.this, "Image saved to Downloads folder", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(MainActivity.this, "Error saving image to Downloads", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveImageWithExifData() {
        try {
            Bitmap bitmap = textureView.getBitmap();
            if (bitmap == null) {
                Toast.makeText(this, "Failed to capture image from TextureView", Toast.LENGTH_SHORT).show();
                return;
            }

            // Get the download folder path
            File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadFolder.exists()) {
                downloadFolder.mkdirs();
            }

            String imageFileName = "camera_capture_" + System.currentTimeMillis() + ".jpg";
            File imageFile = new File(downloadFolder, imageFileName);

            // Save the image to the file
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                fos.flush();

                // Now write Exif data (camera parameters like ISO, exposure time, etc.)
                writeExifData(imageFile);

                Toast.makeText(this, "Image saved to Downloads folder", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error saving image to Downloads folder: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void writeExifData(File imageFile) {
        try {
            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());

            // Set Exif metadata
            exif.setAttribute(ExifInterface.TAG_ISO, String.valueOf(iso));  // ISO
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.valueOf(exposureTime));  // Exposure time
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, whiteBalance);  // White balance

            // You can add more metadata here like shutter speed, lens info, etc.
            exif.saveAttributes();  // Save the Exif data
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error writing Exif data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }


    private String getBackCameraId() {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return null;
    }


}