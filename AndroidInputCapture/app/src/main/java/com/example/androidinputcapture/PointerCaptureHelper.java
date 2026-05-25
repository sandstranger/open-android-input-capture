// File: src/main/java/com/example/androidinputcapture/PointerCaptureHelper.java
package com.example.androidinputcapture;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnCapturedPointerListener;
import android.view.View.OnGenericMotionListener;
import android.view.WindowManager; // Import WindowManager

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.unity3d.player.UnityPlayer;

import java.lang.ref.WeakReference;

public class PointerCaptureHelper implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "PointerCaptureHelper";
    private static final PointerCaptureHelper INSTANCE = new PointerCaptureHelper();
    private static final Object inputLock = new Object();
    private static final boolean DEBUG_MOUSE_LOOK = false;
    private static final long DEBUG_LOG_INTERVAL_MS = 250;

    private static volatile float lastDx = 0, lastDy = 0;
    // Use a queue or list for button presses and scroll deltas if needed,
    // but for simplicity, we'll just store the latest states/deltas for now.
    private static volatile int lastButtonState = 0;
    private static volatile int lastActionButton = 0; // Button that triggered the action
    private static volatile float lastVerticalScrollDelta = 0;
    private static volatile float lastHorizontalScrollDelta = 0;
    private static long nextMouseLookDebugLogTime = 0;


    private static volatile boolean captureRequested = false;
    private static volatile boolean hasCaptureConfirmed = false;

    private WeakReference<Activity> currentActivityRef = new WeakReference<>(null);
    private WeakReference<View> unityViewRef = new WeakReference<>(null);
    private Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private static boolean initialized = false;

    private OnCapturedPointerListener capturedPointerListener = null;
    private OnGenericMotionListener genericMotionListener = null;

    private PointerCaptureHelper() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            capturedPointerListener = (@NonNull View view, @NonNull MotionEvent event) -> {
                // First event confirms capture is active after request
                if (captureRequested && !hasCaptureConfirmed) {
                    Log.d(TAG, "onCapturedPointer: Capture confirmed by first event.");
                    hasCaptureConfirmed = true;
                }
                // Only process if capture is considered active by our helper
                if (!hasCaptureConfirmed) {
                    return false;
                }

                processPointerEvent(event, true);
                return true;
            };
        }

        genericMotionListener = (@NonNull View view, @NonNull MotionEvent event) -> {
            processPointerEvent(event, false);
            return false;
        };
    }

    private static void processPointerEvent(@NonNull MotionEvent event, boolean includeRelativeMovement) {
        int action = event.getAction();

        if (includeRelativeMovement && (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD))) {
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = 0;
                float dy = 0;
                float currentX = event.getX();
                float currentY = event.getY();
                float currentAxisX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
                float currentAxisY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);

                if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        dx += event.getHistoricalX(i);
                        dy += event.getHistoricalY(i);
                    }
                    dx += event.getX();
                    dy += event.getY();
                } else {
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        dx += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i);
                        dy += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i);
                    }
                    dx += event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
                    dy += event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
                }

                synchronized (inputLock) {
                    lastDx += dx;
                    lastDy += dy;
                }

                logMouseLookDebug(event, dx, dy, currentX, currentY, currentAxisX, currentAxisY);
            }
        }

        synchronized (inputLock) {
            lastButtonState = event.getButtonState();
        }
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            synchronized (inputLock) {
                lastActionButton = event.getActionButton();
            }
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            float verticalScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float horizontalScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            synchronized (inputLock) {
                lastVerticalScrollDelta += verticalScroll;
                lastHorizontalScrollDelta += horizontalScroll;
            }
        }
    }

    private static void logMouseLookDebug(
            @NonNull MotionEvent event,
            float dx,
            float dy,
            float currentX,
            float currentY,
            float currentAxisX,
            float currentAxisY) {
        if (!DEBUG_MOUSE_LOOK || dx == 0 && dy == 0) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now < nextMouseLookDebugLogTime) {
            return;
        }

        nextMouseLookDebugLogTime = now + DEBUG_LOG_INTERVAL_MS;
        Log.d(TAG, "ML_NATIVE"
                + " source=0x" + Integer.toHexString(event.getSource())
                + " mouseRelative=" + event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                + " touchpad=" + event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                + " history=" + event.getHistorySize()
                + " get=(" + currentX + "," + currentY + ")"
                + " axis=(" + currentAxisX + "," + currentAxisY + ")"
                + " used=(" + dx + "," + dy + ")"
                + " accumulated=(" + lastDx + "," + lastDy + ")");
    }

    public static void initialize(@NonNull Context context) {
        if (initialized || context == null) {
            return;
        }
        Log.d(TAG, "Initializing PointerCaptureHelper...");
        Application app = (Application) context.getApplicationContext();
        if (app != null) {
            app.registerActivityLifecycleCallbacks(INSTANCE);
            initialized = true;
            Log.d(TAG, "ActivityLifecycleCallbacks registered.");
            Activity currentActivity = UnityPlayer.currentActivity;
            if (currentActivity != null) {
                Log.d(TAG, "Initial check: Found current activity: " + currentActivity.getLocalClassName());
                INSTANCE.tryAttachListener(currentActivity);
            } else {
                Log.d(TAG, "Initial check: No current activity found yet.");
            }
        } else {
            Log.e(TAG, "Initialization failed: Could not get Application context.");
        }
    }

    // --- ActivityLifecycleCallbacks Implementation ---

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        Log.d(TAG, "onActivityResumed: " + activity.getLocalClassName());
        if (activity == UnityPlayer.currentActivity) {
            Log.d(TAG, "Unity Activity Resumed. Trying to attach listener.");
            currentActivityRef = new WeakReference<>(activity);
            tryAttachListener(activity);
        } else {
            // If a different activity is resumed, and we thought we had capture,
            // it means the Unity activity is likely paused or stopped.
            // Reset capture state in this case.
            if (hasCaptureConfirmed || captureRequested) {
                Log.d(TAG, "Different activity resumed, resetting capture state.");
                resetCaptureState();
            }
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        Log.d(TAG, "onActivityPaused: " + activity.getLocalClassName());
        // Check if the paused activity is the one we attached to
        if (activity == currentActivityRef.get()) {
            Log.d(TAG, "Unity Activity Paused. Detaching listener and resetting state.");
            tryDetachListener();
            resetCaptureState();
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        // If the activity we are managing is stopped, reset the state.
        if (activity == currentActivityRef.get()) {
            Log.d(TAG, "Unity Activity Stopped. Resetting state.");
            resetCaptureState();
        }
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle bundle) {

    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        Log.d(TAG, "onActivityDestroyed: " + activity.getLocalClassName());
        // Check if the destroyed activity is the one we were managing
        if (activity == currentActivityRef.get()) {
            Log.d(TAG, "Unity Activity Destroyed. Clearing refs.");
            tryDetachListener(); // Ensure detachment
            resetCaptureState();
            currentActivityRef.clear(); // Now it's safe to clear
            unityViewRef.clear();
        }
    }

    // --- Helper Methods ---

    private void tryAttachListener(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || capturedPointerListener == null) {
            Log.w(TAG, "tryAttachListener: SDK version too low or listener null.");
            return;
        }

        mainThreadHandler.post(() -> {
            View currentUnityView = findUnityView(activity);
            if (currentUnityView != null) {
                View existingView = unityViewRef.get();
                // Check if we are already attached to the *same* view instance
                if (existingView == currentUnityView) {
                    Log.d(TAG, "tryAttachListener: Found same view instance. (Re)attaching listener.");
                } else {
                    Log.d(TAG, "tryAttachListener: Found new view instance or old ref was null. Detaching from old if exists.");
                    tryDetachListener(); // Detach from potentially stale ref
                }

                Log.d(TAG, "tryAttachListener: Attaching listener to view: " + currentUnityView);
                currentUnityView.setFocusable(true);
                currentUnityView.setFocusableInTouchMode(true);
                // THIS IS THE KEY: Attach the listener
                currentUnityView.setOnCapturedPointerListener(capturedPointerListener);
                currentUnityView.setOnGenericMotionListener(genericMotionListener);
                unityViewRef = new WeakReference<>(currentUnityView);

            } else {
                Log.w(TAG, "tryAttachListener: Could not find Unity view to attach listener.");
                unityViewRef.clear(); // Clear stale ref if view wasn't found
            }
        });
    }

    private void tryDetachListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        mainThreadHandler.post(() -> {
            View view = unityViewRef.get();
            if (view != null) {
                Log.d(TAG, "tryDetachListener: Detaching listener from view: " + view);
                view.setOnCapturedPointerListener(null);
                view.setOnGenericMotionListener(null);
            } else {
                Log.d(TAG, "tryDetachListener: No view ref found to detach listener from.");
            }
            // Always clear the ref after attempting detachment
            unityViewRef.clear();
        });
    }


    @Nullable
    private View findUnityView(@Nullable Activity activity) {
        if (activity == null) return null;
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
                View unityChild = ((ViewGroup) content).getChildAt(0);
                // Basic checks if the found view seems like the Unity canvas/view
                if (unityChild != null && unityChild.getClass().getName().contains("Unity")) {
                    return unityChild;
                } else {
                    Log.w(TAG, "findUnityView: Child 0 does not look like Unity view: " + (unityChild != null ? unityChild.getClass().getName() : "null"));
                }
            }
            // Fallback - might be the content view itself in some Unity versions/setups
            if (content != null && content.getClass().getName().contains("Unity")) {
                return content;
            }
            Log.w(TAG, "findUnityView: Could not reliably find a Unity view in activity: " + activity.getLocalClassName());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error finding Unity view", e);
            return null;
        }
    }

    private static void resetCaptureState() {
        if (captureRequested || hasCaptureConfirmed) {
            Log.d(TAG, "Resetting capture state flags and input deltas.");
        }
        captureRequested = false;
        hasCaptureConfirmed = false;
        synchronized (inputLock) {
            lastDx = 0;
            lastDy = 0;
            lastButtonState = 0;
            lastActionButton = 0;
            lastVerticalScrollDelta = 0;
            lastHorizontalScrollDelta = 0;
        }
    }

    // --- Static Methods for Unity ---

    public static void beginCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "beginCapture: Requires API 26+");
            return;
        }

        Log.d(TAG, "beginCapture: Called from Unity. Requesting capture...");
        captureRequested = true; // Mark as requested
        hasCaptureConfirmed = false; // Reset confirmation status
        synchronized (inputLock) {
            lastDx = 0; // Reset deltas on new request
            lastDy = 0;
            lastButtonState = 0; // Reset button state
            lastActionButton = 0; // Reset action button
            lastVerticalScrollDelta = 0; // Reset scroll deltas
            lastHorizontalScrollDelta = 0;
        }


        Activity activity = INSTANCE.currentActivityRef.get();
        // If activity ref is lost, try getting current activity again
        if (activity == null) {
            activity = UnityPlayer.currentActivity;
            if (activity != null) {
                Log.w(TAG, "beginCapture: Activity ref was null, using UnityPlayer.currentActivity: " + activity.getLocalClassName());
                INSTANCE.currentActivityRef = new WeakReference<>(activity);
                // Ensure listener is attached if activity was found now
                INSTANCE.tryAttachListener(activity);
            } else {
                Log.e(TAG, "beginCapture: Cannot capture, Activity is null or not found.");
                INSTANCE.resetCaptureState(); // Capture failed immediately
                return;
            }
        }

        final Activity finalActivity = activity;
        INSTANCE.mainThreadHandler.post(() -> {
            View view = INSTANCE.unityViewRef.get();
            // Also try finding view again if ref is stale but activity is valid
            if (view == null) {
                Log.w(TAG, "beginCapture (UI Thread): View ref was null, trying to find view again.");
                view = INSTANCE.findUnityView(finalActivity);
                if (view != null) {
                    Log.w(TAG, "beginCapture (UI Thread): Found view: " + view);
                    INSTANCE.unityViewRef = new WeakReference<>(view);
                    // Ensure listener is attached if we just found the view
                    // tryAttachListener handles re-attaching if necessary
                    INSTANCE.tryAttachListener(finalActivity); // Pass activity
                } else {
                    Log.e(TAG, "beginCapture (UI Thread): Failed to find Unity view to request capture.");
                    INSTANCE.resetCaptureState(); // Capture failed
                    return; // Cannot proceed without view
                }
            }

            // Now we have a view, and tryAttachListener should have ensured the listener is attached
            if (view != null) {
                Log.d(TAG, "beginCapture (UI Thread): Requesting focus and capture on view: " + view);
                // Ensure focus before requesting capture
                view.requestFocus();
                view.requestFocusFromTouch(); // Added this line

                if (view.hasPointerCapture()) {
                    Log.d(TAG, "beginCapture (UI Thread): View already reports having pointer capture.");
                    // If it already reports having capture, assume it's active for our state tracking
                    hasCaptureConfirmed = true;
                } else {
                    Log.d(TAG, "beginCapture (UI Thread): Calling requestPointerCapture() with 100ms delay.");
                    // Capture the view variable as final for use in the delayed lambda
                    final View finalViewForDelayedCapture = view;
                    INSTANCE.mainThreadHandler.postDelayed(() -> {
                        // This is the delayed call
                        if (finalViewForDelayedCapture != null) { // Use the final variable
                            Log.d(TAG, "beginCapture (UI Thread, delayed): Calling requestPointerCapture() now.");
                            finalViewForDelayedCapture.requestPointerCapture();
                            // We wait for the first onCapturedPointer event to set hasCaptureConfirmed = true
                        } else {
                            Log.w(TAG, "beginCapture (UI Thread, delayed): View became null before delayed capture request.");
                            INSTANCE.resetCaptureState(); // Capture failed
                        }
                    }, 100); // 100ms delay
                }
            } // Else branch handled above if view is null
        });
    }

    public static void endCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        Log.d(TAG, "endCapture: Called from Unity. Releasing capture...");
        // Immediately update state based on the release request
        resetCaptureState();

        Activity activity = INSTANCE.currentActivityRef.get();
        // If activity ref is lost, try to get current one just for release
        if (activity == null) {
            activity = UnityPlayer.currentActivity;
            if (activity != null) {
                Log.w(TAG, "endCapture: Activity ref was null, using UnityPlayer.currentActivity: " + activity.getLocalClassName());
                INSTANCE.currentActivityRef = new WeakReference<>(activity);
            } else {
                Log.w(TAG, "endCapture: Activity is null, cannot release via UI thread.");
                return;
            }
        }

        final Activity finalActivity = activity;
        INSTANCE.mainThreadHandler.post(() -> {
            View view = INSTANCE.unityViewRef.get();
            // Try finding view again if ref is stale but activity valid
            if (view == null) {
                Log.w(TAG, "endCapture (UI Thread): View ref was null during release, trying to find it.");
                view = INSTANCE.findUnityView(finalActivity);
                if (view != null) {
                    Log.w(TAG, "endCapture (UI Thread): Found view for release: " + view);
                    // Don't need to re-attach listener during release
                    INSTANCE.unityViewRef = new WeakReference<>(view);
                } else {
                    Log.w(TAG, "endCapture (UI Thread): Could not find Unity view to release capture from.");
                    return; // Cannot proceed without view
                }
            }

            if (view != null) {
                if (view.hasPointerCapture()) {
                    Log.d(TAG, "endCapture (UI Thread): Calling releasePointerCapture() on view: " + view);
                    view.releasePointerCapture();
                } else {
                    Log.d(TAG, "endCapture (UI Thread): View did not report having capture, but state was reset.");
                }
            } // Else branch handled above if view is null
        });
    }

    public static boolean isPointerCaptured() {
        return hasCaptureConfirmed;
    }

    public static float getLastDx() {
        synchronized (inputLock) {
            float tmp = lastDx;
            lastDx = 0; // Consume the delta
            return tmp;
        }
    }

    public static float getLastDy() {
        synchronized (inputLock) {
            float tmp = lastDy;
            lastDy = 0; // Consume the delta
            return tmp;
        }
    }

    /**
     * Returns the latest button state bitfield.
     * See MotionEvent.getButtonState() for button constants (e.g., MotionEvent.BUTTON_PRIMARY).
     */
    public static int getLastButtonState() {
        synchronized (inputLock) {
            return lastButtonState;
        }
    }

    /**
     * Returns the button that triggered the latest ACTION_DOWN or ACTION_UP event.
     * See MotionEvent.getActionButton() for button constants.
     */
    public static int getLastActionButton() {
        synchronized (inputLock) {
            int tmp = lastActionButton;
            lastActionButton = 0; // Consume the action button
            return tmp;
        }
    }

    /**
     * Returns the latest vertical scroll delta.
     * Value is typically -1.0 for down, 1.0 for up.
     */
    public static float getLastVerticalScrollDelta() {
        synchronized (inputLock) {
            float tmp = lastVerticalScrollDelta;
            lastVerticalScrollDelta = 0; // Consume the delta
            return tmp;
        }
    }

    /**
     * Returns the latest horizontal scroll delta.
     * Value depends on the input device, often -1.0 for left, 1.0 for right.
     */
    public static float getLastHorizontalScrollDelta() {
        synchronized (inputLock) {
            float tmp = lastHorizontalScrollDelta;
            lastHorizontalScrollDelta = 0; // Consume the delta
            return tmp;
        }
    }


    // --- Unused ActivityLifecycleCallbacks methods ---
    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(@NonNull Activity activity) {}
}
