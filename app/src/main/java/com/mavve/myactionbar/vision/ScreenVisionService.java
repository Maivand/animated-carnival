package com.mavve.myactionbar.vision;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import com.mavve.myactionbar.R;
import com.mavve.myactionbar.voice.RealtimeVoiceSession;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen-vision overlay. A foreground service that mirrors the screen through
 * MediaProjection and floats a small bubble over whatever app the user is in.
 * Tapping the bubble grabs the current frame and hands it to the live realtime
 * session, so Jarvis can see what the user is looking at and talk about it.
 *
 * Start it from the activity after the user grants the projection consent and
 * the draw-over-apps permission.
 */
public class ScreenVisionService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "jarvis_vision";
    private static final int NOTIF_ID = 91;
    private static final int MAX_WIDTH = 1024;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private View bubble;
    private int captureWidth;
    private int captureHeight;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, null);

        setupCapture();
        showBubble();
        return START_STICKY;
    }

    // ---- screen capture ---------------------------------------------------

    private void setupCapture() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        // Downscale to keep the frame small enough to send quickly.
        float scale = Math.min(1f, (float) MAX_WIDTH / metrics.widthPixels);
        captureWidth = Math.max(1, Math.round(metrics.widthPixels * scale));
        captureHeight = Math.max(1, Math.round(metrics.heightPixels * scale));

        captureThread = new HandlerThread("jarvis-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(captureWidth, captureHeight,
                PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("jarvis-vision",
                captureWidth, captureHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
    }

    private void captureAndSend() {
        captureHandler.post(() -> {
            String base64 = grabFrame();
            RealtimeVoiceSession session = RealtimeSessionHolder.get();
            if (base64 == null) {
                return;
            }
            if (session != null) {
                session.sendImage(base64, null);
            }
        });
    }

    private String grabFrame() {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * captureWidth;
            Bitmap full = Bitmap.createBitmap(
                    captureWidth + rowPadding / pixelStride, captureHeight,
                    Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, captureWidth, captureHeight);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 70, out);
            full.recycle();
            cropped.recycle();
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    // ---- floating bubble --------------------------------------------------

    private void showBubble() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                (int) (64 * getResources().getDisplayMetrics().density),
                (int) (64 * getResources().getDisplayMetrics().density),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 24;
        params.y = 240;

        Button button = new Button(this);
        button.setText("👁");
        button.setBackgroundResource(R.drawable.orb_idle);

        // Tap to look; drag to move.
        button.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                            moved = true;
                        }
                        params.x = startX + dx;
                        params.y = startY + dy;
                        windowManager.updateViewLayout(bubble, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            captureAndSend();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        bubble = button;
        windowManager.addView(bubble, params);
    }

    // ---- lifecycle --------------------------------------------------------

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Jarvis screen vision", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Jarvis is watching your screen")
                    .setContentText("Tap the eye to have Jarvis look.")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("Jarvis screen vision")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    @Override
    public void onDestroy() {
        try {
            if (bubble != null && windowManager != null) {
                windowManager.removeView(bubble);
            }
        } catch (Exception ignored) {
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (imageReader != null) {
            imageReader.close();
        }
        if (projection != null) {
            projection.stop();
        }
        if (captureThread != null) {
            captureThread.quitSafely();
        }
        super.onDestroy();
    }
}
