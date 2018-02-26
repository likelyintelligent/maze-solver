package com.likelyintelligent.mazesolver;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

import Catalano.Imaging.Concurrent.Filters.BradleyLocalThreshold;
import Catalano.Imaging.Concurrent.Filters.Grayscale;
import Catalano.Imaging.FastBitmap;

/**
 * Image Capture Activity
 *
 * This activity takes care of capturing the image of the maze
 *
 * ----
 * Brainstorming:
 * - show the live preview at a smaller resolution to make the preview
 *   more real time.  Maybe 640 x 480 stretched to fit the canvas
 * - take the picture at full resolution and process that image
 *   to the desired thresholding and ship it off to the maze solver
 */
public class ImageCaptureActivity extends AppCompatActivity {

    private static final Size DESIRED_STREAM_SIZE = new Size(640, 480);

    private View mControlsView;
    private TextureView captureView;
    private Button captureButton;
    private CameraDevice camera;
    private Size cameraSize;
    private Handler backgroundHandler;
    private ImageReader imageReader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_image_capture);
        mControlsView = findViewById(R.id.fullscreen_content_controls);
        captureView = findViewById(R.id.capture_view);
        captureButton = findViewById(R.id.capture_button);

        captureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                initCamera();
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
        });

    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
    }

    @Nullable
    private void initCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            final String cameraId = getCameraId(manager);
            if (cameraId == null)
                return;

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
//            cameraSize = streamMap.getOutputSizes(SurfaceTexture.class)[0];
            cameraSize = ImageCaptureActivity.DESIRED_STREAM_SIZE;

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                // Camera permissions have not been granted
                return;
            }

            HandlerThread thread = new HandlerThread("camera_background_thread");
            thread.start();
            backgroundHandler = new Handler(thread.getLooper());
            CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    ImageCaptureActivity.this.camera = camera;
                    try {
                        startCameraPreview();
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    ImageCaptureActivity.this.camera = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    ImageCaptureActivity.this.camera = null;
                }
            };
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private String getCameraId(final CameraManager manager) throws CameraAccessException {
        final String[] cameraIds = manager.getCameraIdList();
        final String cameraId = cameraIds[0];
        return cameraId;
    }

    private static Bitmap yuvToBitmap(final Image image) {
        // convert the yuv image to an nv21 byte array
        final ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        final ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        final ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
        final int ySize = yBuffer.remaining();
        final int uSize = uBuffer.remaining();
        final int vSize = vBuffer.remaining();
        final byte[] data = new byte[ySize + uSize + vSize];
        yBuffer.get(data, 0, ySize);
        vBuffer.get(data, ySize, vSize);
        uBuffer.get(data, ySize + vSize, uSize);

        // convert the yuv byte array to a jpeg byte array and then a bitmap
        // image.
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21,
                image.getWidth(), image.getHeight(), null);
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()),
                100, out);
        final byte[] jpegData = out.toByteArray();
        // make sure the produced image is mutable so that
        // Catalano can process it
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length, options);
    }

    private static Bitmap processBitmap(final Bitmap bitmap) {
        FastBitmap fastBitmap = new FastBitmap(bitmap);
        Grayscale grayscale = new Grayscale();
        grayscale.applyInPlace(fastBitmap);
        BradleyLocalThreshold bradley = new BradleyLocalThreshold();
        bradley.applyInPlace(fastBitmap);
        return fastBitmap.toBitmap();
    }

    private static void drawBitmapOnCanvas(final Canvas canvas, final Bitmap bitmap) {
        // the image comes in landscape, so it has to be rotated
        // 90 degrees and translated to become a profile image
        float scale = (float) canvas.getHeight() / bitmap.getWidth();
        final Matrix matrix = new Matrix();
        matrix.postScale(scale, scale, 0, 0);
        matrix.postRotate(90, 0, 0);
        matrix.postTranslate(bitmap.getHeight() * scale, 0);
        canvas.drawBitmap(bitmap, matrix, null);
    }

    private void startCameraPreview() throws CameraAccessException {
        imageReader = ImageReader.newInstance(cameraSize.getWidth(), cameraSize.getHeight(),
                ImageFormat.YUV_420_888, 3);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                final Image image = reader.acquireLatestImage();
                if(image == null)
                    return;

                // convert the YUV image to an RGB bitmap
                // and process the bitmap
                Bitmap bitmap = processBitmap(yuvToBitmap(image));

                // draw the image to the canvas of the texture view
                final Canvas canvas = captureView.lockCanvas();
                ImageCaptureActivity.drawBitmapOnCanvas(canvas, bitmap);
                captureView.unlockCanvasAndPost(canvas);
                image.close();
            }
        }, backgroundHandler);
        Surface surface = imageReader.getSurface();
        final CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(surface);

        camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                if(camera == null)
                    return;

                CaptureRequest request = builder.build();
                try {
                    session.setRepeatingRequest(request, null, backgroundHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {

            }
        }, backgroundHandler);
    }
}