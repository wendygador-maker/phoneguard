package com.phoneguard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCapture {

    private static final String TAG = "PhoneGuard";
    private static ScreenCapture instance;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int width;
    private int height;
    private int density;
    private HandlerThread handlerThread;
    private Handler handler;
    private volatile Image latestImage;

    public static ScreenCapture getInstance() {
        if (instance == null) {
            instance = new ScreenCapture();
        }
        return instance;
    }

    public boolean isReady() {
        return mediaProjection != null;
    }

    public void init(Context context, int resultCode, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager)
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, data);

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection");
            return;
        }

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        density = metrics.densityDpi;

        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                if (latestImage != null) {
                    latestImage.close();
                }
                latestImage = img;
            }
        }, handler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "PhoneGuard",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, handler);

        Log.i(TAG, "Screen capture initialized: " + width + "x" + height);
    }

    public byte[] takeScreenshot(int quality, float scale) {
        if (!isReady()) return null;

        // Wait a bit for a fresh frame
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        Image image = latestImage;
        if (image == null) return null;

        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop padding
            if (rowPadding > 0) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            }

            // Scale if needed
            if (scale > 0 && scale < 1.0f) {
                int sw = (int) (width * scale);
                int sh = (int) (height * scale);
                bitmap = Bitmap.createScaledBitmap(bitmap, sw, sh, true);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, quality > 0 ? quality : 80, baos);
            bitmap.recycle();

            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Screenshot failed", e);
            return null;
        }
    }

    public void release() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        if (latestImage != null) {
            latestImage.close();
            latestImage = null;
        }
        instance = null;
    }
}
