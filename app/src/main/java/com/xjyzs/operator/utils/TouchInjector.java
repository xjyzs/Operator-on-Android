package com.xjyzs.operator.utils;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.net.LocalSocket;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import androidx.annotation.Keep;

import java.io.DataInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Keep
@SuppressLint("PrivateApi,BlockedPrivateApi,SoonBlockedPrivateApi,DiscouragedPrivateApi")
public class TouchInjector {

    private static final String PACKAGE_NAME = "com.android.shell";
    private static final int DEFAULT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;

    private static InputManager inputManager;
    private static Method injectInputEventMethod;
    private static Method setDisplayIdMethod;

    private static final MotionEvent.PointerProperties[] POINTER_PROPERTIES = new MotionEvent.PointerProperties[1];
    private static final MotionEvent.PointerCoords[] POINTER_COORDS = new MotionEvent.PointerCoords[1];
    private static long currentDownTime = 0;

    static {
        try {
            Looper.prepare();
            synchronized (Looper.class) {
                Field sMainLooperField = Looper.class.getDeclaredField("sMainLooper");
                sMainLooperField.setAccessible(true);
                sMainLooperField.set(null, Looper.myLooper());
            }

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Constructor<?> activityThreadConstructor = activityThreadClass.getDeclaredConstructor();
            activityThreadConstructor.setAccessible(true);
            Object activityThread = activityThreadConstructor.newInstance();

            Field sCurrentActivityThreadField = activityThreadClass.getDeclaredField("sCurrentActivityThread");
            sCurrentActivityThreadField.setAccessible(true);
            sCurrentActivityThreadField.set(null, activityThread);

            Field mSystemThreadField = activityThreadClass.getDeclaredField("mSystemThread");
            mSystemThreadField.setAccessible(true);
            mSystemThreadField.setBoolean(activityThread, true);

            try {
                Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
                Constructor<?> appBindDataConstructor = appBindDataClass.getDeclaredConstructor();
                appBindDataConstructor.setAccessible(true);
                Object appBindData = appBindDataConstructor.newInstance();

                android.content.pm.ApplicationInfo applicationInfo = new android.content.pm.ApplicationInfo();
                applicationInfo.packageName = PACKAGE_NAME;

                Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
                appInfoField.setAccessible(true);
                appInfoField.set(appBindData, applicationInfo);

                Field mBoundApplicationField = activityThreadClass.getDeclaredField("mBoundApplication");
                mBoundApplicationField.setAccessible(true);
                mBoundApplicationField.set(activityThread, appBindData);
            } catch (Exception ignored) {
            }

            Method getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext");
            Context systemContext = (Context) getSystemContextMethod.invoke(activityThread);

            ContextWrapper fakeContext = new ContextWrapper(systemContext) {
                @Override
                public String getPackageName() { return PACKAGE_NAME; }
                @Override
                public String getOpPackageName() { return PACKAGE_NAME; }
                @Override
                public int checkCallingPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
                @TargetApi(31)
                @Override
                public AttributionSource getAttributionSource() {
                    return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE_NAME).build();
                }
                @Override
                public Context getApplicationContext() { return this; }
            };

            try {
                android.app.Application app = android.app.Instrumentation.newApplication(android.app.Application.class, fakeContext);
                Field mInitialApplicationField = activityThreadClass.getDeclaredField("mInitialApplication");
                mInitialApplicationField.setAccessible(true);
                mInitialApplicationField.set(activityThread, app);
            } catch (Exception ignored) {
            }

            inputManager = (InputManager) fakeContext.getSystemService(Context.INPUT_SERVICE);
            injectInputEventMethod = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);

            POINTER_PROPERTIES[0] = new MotionEvent.PointerProperties();
            POINTER_PROPERTIES[0].id = 0;
            POINTER_PROPERTIES[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
            POINTER_COORDS[0] = new MotionEvent.PointerCoords();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static MotionEvent obtainTouchEvent(int action, float x, float y, float pressure) {
        POINTER_COORDS[0].x = x;
        POINTER_COORDS[0].y = y;
        POINTER_COORDS[0].pressure = pressure;
        POINTER_COORDS[0].size = 1.0f;
        return MotionEvent.obtain(currentDownTime, SystemClock.uptimeMillis(), action, 1,
                POINTER_PROPERTIES, POINTER_COORDS, 0, 0, 1.0f, 1.0f, 0, 0, DEFAULT_SOURCE, 0);
    }

    private static boolean inject(MotionEvent event, int displayId, int mode) {
        try {
            setDisplayIdMethod.invoke(event, displayId);
            return (boolean) injectInputEventMethod.invoke(inputManager, event, mode);
        } catch (Exception e) {
            return false;
        } finally {
            event.recycle();
        }
    }

    private static synchronized void handleCommand(int action, int x, int y, int displayId) {
        switch (action) {
            case 0: // down
                if (currentDownTime != 0) {
                    inject(obtainTouchEvent(MotionEvent.ACTION_CANCEL, x, y, 0), displayId, 0);
                }
                currentDownTime = SystemClock.uptimeMillis();
                inject(obtainTouchEvent(MotionEvent.ACTION_DOWN, x, y, 1.0f), displayId, 2);
                break;
            case 1: // move
                if (currentDownTime == 0) return;
                inject(obtainTouchEvent(MotionEvent.ACTION_MOVE, x, y, 1.0f), displayId, 0);
                break;
            case 2: // up
                if (currentDownTime == 0) return;
                inject(obtainTouchEvent(MotionEvent.ACTION_UP, x, y, 0), displayId, 0);
                currentDownTime = 0;
                break;
        }
    }

    public static void main(String[] args) {
        try {
            LocalSocket socket = new LocalSocket();
            socket.connect(new android.net.LocalSocketAddress("touch_injector"));
            DataInputStream reader = new DataInputStream(socket.getInputStream());
            while (true) {
                int action = reader.readByte() & 0xFF;
                if (action == 3) break;
                int x = reader.readUnsignedShort();
                int y = reader.readUnsignedShort();
                int displayId = reader.readUnsignedShort();
                handleCommand(action, x, y, displayId);
            }
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
