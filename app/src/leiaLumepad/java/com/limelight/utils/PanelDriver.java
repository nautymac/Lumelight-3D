package com.limelight.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.util.Log;
import android.view.Surface;
import android.view.View;

import com.leia.core.LogLevel;
import com.leia.sdk.LeiaSDK;
import com.leia.sdk.views.InputViewsAsset;
import com.leia.sdk.views.InterlacedSurfaceView;
import com.leia.sdk.views.InterlacedSurfaceViewConfigAccessor;
import com.leia.sdk.views.ScaleType;


/**
 * Leia build of the panel driver, for lightfield panels such as the Lume Pad 2.
 *
 * These panels do not weave the two eyes into fixed screen columns the way a bonded
 * lenticular lens does. The backlight is a diffractive grating that steers its views, and
 * where each view lands depends on where the viewer's face is, tracked by the front cameras.
 * No static shader can follow that, so the weave belongs to Leia's CNSDK: this class stands
 * up an {@link InterlacedSurfaceView}, hands the stream a surface to decode into, and keeps
 * the backlight and the face tracker in step with the app's lifecycle.
 *
 * The lenticular flavour has a do-nothing version of this class with the same shape. Only
 * this one links against CNSDK, which keeps Leia's proprietary code out of a shareable build.
 */
public final class PanelDriver {

    private static final String TAG = "PanelDriver";

    /** Receives the surface the stream should be decoded into, once the panel has one. */
    public interface SurfaceListener {
        void onPanelSurfaceReady(Surface surface);
    }

    /** Told where to draw once the converter has somewhere to take a single view. */
    public interface ConversionListener {
        void onConversionInputReady(Surface input, int width, int height);
    }

    // CNSDK is a process-wide singleton, and both Game and StreamContainer want a driver, so
    // hand out one wrapper rather than initialising the SDK twice.
    private static PanelDriver instance;
    private static boolean initFailed;

    private final LeiaSDK sdk;
    private InterlacedSurfaceView view;
    // Held weakly: the driver outlives nothing, but it is reached from SDK callbacks that do
    // not say which activity they belong to, and brightness is a property of a window.
    private java.lang.ref.WeakReference<Activity> activityRef;
    // Whether this driver is currently holding the window at full brightness, so the release
    // can tell a real change from a repeat.
    private boolean brightnessPinned;

    // The application, not an activity: the brightness rewrite runs a second after the
    // activity may already be gone.
    private static Context appContext;
    private static final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final String PREF_ASKED_BRIGHTNESS = "leia_asked_brightness_permission";
    private Object conversionEngine;
    private Surface conversionInput;
    private boolean ready;
    private boolean enabled;
    private boolean hasFocus = true;
    private boolean sideBySide;
    private int sourceWidth;
    private int sourceHeight;

    private PanelDriver(LeiaSDK sdk) {
        this.sdk = sdk;
    }

    /**
     * @return the driver for this device's panel, or null when CNSDK will not come up, in
     *         which case the caller falls back to the shader weave.
     */
    public static synchronized PanelDriver get(Context context) {
        if (instance != null || initFailed) {
            return instance;
        }

        Log.i(TAG, "Bringing up CNSDK for the panel");
        reportConversionEngine(context);
        try {
            LeiaSDK sdk = LeiaSDK.getInstance();
            if (sdk == null) {
                LeiaSDK.InitArgs initArgs = new LeiaSDK.InitArgs();

                // All four of these matter. Passing only the application, as an earlier
                // version did, made createSDK hand back null without so much as an
                // exception, and without even loading its native library.
                initArgs.platform.app = (Application) context.getApplicationContext();
                initArgs.platform.context = context;
                if (context instanceof Activity) {
                    initArgs.platform.activity = (Activity) context;
                }
                initArgs.platform.logLevel = LogLevel.Default;

                // The panel steers its views from the viewer's face, so tracking is the
                // whole point here. Leaving the permission check on lets CNSDK put up its
                // own camera prompt rather than failing silently when the grant is missing.
                initArgs.enableFaceTracking = true;
                initArgs.requiresFaceTrackingPermissionCheck = true;
                initArgs.faceTrackingServerLogLevel = LogLevel.Default;

                // Initialisation runs on: reading the panel's calibration and binding to
                // the backlight service both take a moment. The SDK comes back before any
                // of that has finished, so the driver waits for this callback rather than
                // asking isInitialized() and concluding the panel is not a Leia one.
                initArgs.delegate = new LeiaSDK.Delegate() {
                    @Override
                    public void didInitialize(LeiaSDK initialised) {
                        Log.i(TAG, "Leia lightfield panel ready");
                        onInitialised(initialised);
                    }

                    // CNSDK 0.8 added a licence check. It is off unless InitArgs asks for it,
                    // which this does not, but if it ever refuses the panel will simply stay
                    // flat, so say so where it can be seen.
                    @Override
                    public void invalidLicense(LeiaSDK sdk, String reason) {
                        Log.e(TAG, "CNSDK refused its licence: " + reason);
                    }

                    @Override
                    public void onFaceTrackingStarted(LeiaSDK sdk) {
                        Log.i(TAG, "Face tracking started");
                    }

                    @Override
                    public void onFaceTrackingStopped(LeiaSDK sdk) {
                        Log.i(TAG, "Face tracking stopped");
                    }

                    @Override
                    public void onFaceTrackingFatalError(LeiaSDK sdk) {
                        Log.w(TAG, "Face tracking hit a fatal error; depth will not follow the viewer");
                    }
                };

                sdk = LeiaSDK.createSDK(initArgs);
            }

            if (sdk == null) {
                Log.w(TAG, "createSDK returned null; this is probably not a Leia panel");
                initFailed = true;
                return null;
            }

            // The panel is a Leia one, so this driver owns the weave from here even while
            // the SDK is still starting up. Anything asked of it before didInitialize
            // arrives is held back by pendingState().
            instance = new PanelDriver(sdk);
            appContext = context.getApplicationContext();
            if (context instanceof Activity) {
                instance.activityRef = new java.lang.ref.WeakReference<>((Activity) context);
            }
            return instance;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to bring up CNSDK", t);
            initFailed = true;
            return null;
        }
    }

    /** Applies whatever was asked for while the SDK was still coming up. */
    private static synchronized void onInitialised(LeiaSDK sdk) {
        try {
            // Left to itself the panel drops out of 3D the moment the tracker loses the
            // face, and comes back when it finds it again. Turning your head, or a change
            // in the light, is enough. What that looks like from the sofa is 3D that works
            // sometimes and not others.
            sdk.enableNoFaceMode(false);

            // A session that ended without putting the panel back leaves it in 3D. Clear it
            // here and let applyState turn it on again if this stream actually wants it.
            sdk.enableBacklight(false);
        } catch (Throwable t) {
            Log.w(TAG, "Could not settle the panel's initial state: " + t);
        }

        if (instance != null) {
            instance.ready = true;
            instance.applyState();
        }
    }

    /**
     * Says whether the panel's own 2D-to-3D conversion can be reached from here.
     *
     * The engine is not part of CNSDK: it lives in a separate system package and is loaded
     * into this process at runtime, so its absence is a normal outcome rather than a fault.
     * Nothing depends on the answer yet -- it is logged so the next step has ground to
     * stand on.
     */
    private static void reportConversionEngine(Context context) {
        try {
            com.leiainc.leiamediasdk.LeiaMediaSDK media =
                    com.leiainc.leiamediasdk.LeiaMediaSDK.getInstance(context);
            if (media == null) {
                Log.i(TAG, "No Leia conversion engine on this device");
                return;
            }
            android.content.Context service =
                    com.leiainc.leiamediasdk.LeiaMediaSDK.serviceContext(context);
            Log.i(TAG, "Leia conversion engine available, service context "
                    + (service == null ? "missing" : "ready"));
        } catch (Throwable t) {
            Log.w(TAG, "Could not look for the Leia conversion engine: " + t);
        }
    }

    /**
     * Trims a single view down to what the panel can resolve per eye.
     *
     * The converter's cost follows the size it is handed, and this panel reports 1920x1200 as
     * what one eye actually sees. Anything past that is detail computed and then thrown away,
     * at the price of frames: handed the stream's full 2560 width it managed about fifteen a
     * second. The stream itself is untouched -- the host still renders at full size, so text
     * arrives sharp and is scaled down here rather than drawn small there, which is what made
     * lowering the stream resolution look broken.
     */
    private int[] limitToViewResolution(int width, int height) {
        try {
            if (sdk != null) {
                com.leia.sdk.Config config = sdk.getConfig();
                com.leia.core.Vector2i perEye = config == null ? null : config.getViewResolution();
                if (perEye != null && perEye.x > 0 && perEye.y > 0 && width > perEye.x) {
                    int limited = (int) Math.round(height * (double) perEye.x / width);
                    return new int[]{perEye.x, limited};
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not read the panel's view resolution: " + t);
        }
        return new int[]{width, height};
    }

    /**
     * Hands the panel's surface to its own converter, and asks for somewhere to put a single
     * view in return.
     *
     * The converter is built on a thread of its own because the call blocks while its
     * rendering thread comes up -- around a second, and on the main thread that is a frozen
     * screen. It also has to be the only thing holding the surface: an EGL window surface
     * cannot be made twice over the same one, so nothing else may be drawing there.
     *
     * Returns false when there is no converter to use, which is the ordinary outcome on any
     * device but this one, and leaves the caller to draw the eyes itself.
     */
    public boolean startConversion(Context context, Surface out, int eyeWidth, int eyeHeight,
                                   float strength, ConversionListener listener) {
        final com.leiainc.leiamediasdk.LeiaMediaSDK media =
                com.leiainc.leiamediasdk.LeiaMediaSDK.getInstance(context);
        if (media == null) {
            return false;
        }
        final Context service = com.leiainc.leiamediasdk.LeiaMediaSDK.serviceContext(context);
        if (service == null) {
            return false;
        }

        final int[] size = limitToViewResolution(eyeWidth, eyeHeight);
        final int inputWidth = size[0];
        final int inputHeight = size[1];

        new Thread(() -> {
            long started = System.currentTimeMillis();
            Object engine = media.createMonoVideoSurfaceRenderer(service, out,
                    surfaceTexture -> {
                        // Its default is a square, which would squash the picture; the
                        // converter wants one eye's worth.
                        surfaceTexture.setDefaultBufferSize(inputWidth, inputHeight);
                        Surface input = new Surface(surfaceTexture);
                        synchronized (PanelDriver.class) {
                            conversionInput = input;
                        }
                        listener.onConversionInputReady(input, inputWidth, inputHeight);
                    });
            if (engine == null) {
                Log.w(TAG, "The panel's converter would not start");
                return;
            }
            synchronized (PanelDriver.class) {
                conversionEngine = engine;
            }
            applyStrength(engine, strength);
            Log.i(TAG, "Panel converter ready in " + (System.currentTimeMillis() - started)
                    + "ms, one view at " + inputWidth + "x" + inputHeight);
        }, "leia-converter").start();

        return true;
    }

    /**
     * How much depth the converter makes, from the 3D Effect Strength setting.
     *
     * That setting used to reach only the depth shader, which this path skips, so on this
     * panel it moved and nothing changed. 0.3 is the gain p3d-lumepad settled on after
     * comparing it against real side by side footage, and it lands at the slider's midpoint,
     * which is where the setting starts; the far end doubles it. Convergence is left to the
     * converter, which re-aims it scene by scene -- fixing it by hand was tried there and was
     * worse.
     */
    private static void applyStrength(Object engine, float strength) {
        if (!(engine instanceof com.leiainc.leiamediasdk.interfaces.MonoVideoSurfaceRenderer)) {
            return;
        }
        float gain = 0.3f * Math.max(0f, strength);
        try {
            ((com.leiainc.leiamediasdk.interfaces.MonoVideoSurfaceRenderer) engine)
                    .setGainMultiplier(gain);
            Log.i(TAG, "Panel converter depth gain " + gain);
        } catch (Throwable t) {
            Log.w(TAG, "Could not set the converter's depth: " + t);
        }
    }

    /** Lets the converter go. Blocks on its rendering thread, so keep it off the main one. */
    public void stopConversion() {
        Object engine;
        Surface input;
        synchronized (PanelDriver.class) {
            engine = conversionEngine;
            input = conversionInput;
            conversionEngine = null;
            conversionInput = null;
        }
        if (engine instanceof com.leiainc.leiamediasdk.interfaces.MonoVideoSurfaceRenderer) {
            try {
                ((com.leiainc.leiamediasdk.interfaces.MonoVideoSurfaceRenderer) engine).release();
            } catch (Throwable t) {
                Log.w(TAG, "Could not release the panel's converter: " + t);
            }
        }
        if (input != null) {
            input.release();
        }
    }

    /** True: on this panel CNSDK produces the interlaced frame, not the shader. */
    public boolean ownsInterlacing() {
        return true;
    }

    /**
     * Builds the view that shows the stream. What comes back draws the woven frame; the
     * stream itself is decoded into the surface handed to {@code listener}, which arrives
     * once CNSDK has one to give.
     */
    public View createStreamView(Context context, int width, int height,
                                 SurfaceListener listener) {
        try {
            InterlacedSurfaceView created = new InterlacedSurfaceView(context);

            sourceWidth = width;
            sourceHeight = height;

            // CNSDK owns the texture the frames land in, and tells us when it is ready. The
            // size has to go in here: a texture left at its default size crops the stream
            // to whatever corner happens to fit, which is what the picture did before.
            InputViewsAsset asset = new InputViewsAsset();
            asset.CreateEmptySurfaceForVideo(width, height, surfaceTexture -> {
                surfaceTexture.setDefaultBufferSize(width, height);
                listener.onPanelSurfaceReady(new Surface(surfaceTexture));
            });
            created.setViewAsset(asset);

            view = created;
            applySourceSize();
            applyTiling();
            return created;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to create the Leia stream view", t);
            return null;
        }
    }

    /**
     * Tells the panel how to read the frames it is given: side by side means two views to
     * pull apart, otherwise the frame is a single view and the panel shows it flat.
     */
    public void setStereoContent(boolean sideBySide) {
        this.sideBySide = sideBySide;
        applyTiling();
    }

    /**
     * Tells the panel the size of the frames it is being given. Without this it has no idea
     * what shape the stream is and scales it to fit its own guess, which showed up as a
     * wildly cropped and magnified picture.
     */
    public void setSourceSize(int width, int height) {
        sourceWidth = width;
        sourceHeight = height;
        applySourceSize();
    }

    private void applySourceSize() {
        if (view == null || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        try {
            view.setSourceSize(sourceWidth, sourceHeight);
            view.setScaleType(ScaleType.FIT_CENTER);
            Log.i(TAG, "Panel source size " + sourceWidth + "x" + sourceHeight);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to set the panel source size", t);
        }
    }

    public void set3DMode(boolean enabled) {
        this.enabled = enabled;
        applyState();
    }

    public void onResume() {
        try {
            sdk.onResume();
            if (view != null) {
                view.onResume();
            }
        } catch (Throwable t) {
            Log.w(TAG, "CNSDK onResume failed: " + t);
        }
        applyState();
    }

    public void onPause() {
        try {
            // Drop the backlight and the camera before going to the background, so another
            // app is not left looking at a panel still running in 3D. Brightness goes first,
            // for the reason given in applyState.
            applyBrightness(false);
            sdk.enableBacklight(false);
            sdk.enableFaceTracking(false);
            if (view != null) {
                view.onPause();
            }
            sdk.onPause();
        } catch (Throwable t) {
            Log.w(TAG, "CNSDK onPause failed: " + t);
        }
    }

    /**
     * Takes CNSDK down with the activity that started it.
     *
     * The driver is a process-wide singleton but the SDK is not free of the activity it was
     * built with: that goes into InitArgs, and a stream leaving takes the activity with it.
     * Held past that, the SDK keeps a reference to something destroyed, and the next stream
     * asks it to track faces and is told no -- with the camera free and no other app in the
     * way, which is a confusing place to start looking. Building it again costs about a
     * tenth of a second and gets an SDK that belongs to the activity now on screen.
     */
    public static synchronized void shutdown() {
        PanelDriver driver = instance;
        if (driver == null) {
            return;
        }
        instance = null;
        initFailed = false;

        driver.applyBrightness(false);
        driver.stopConversion();
        try {
            if (driver.view != null) {
                driver.view.releaseInputViewsAsset();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not release the panel's view asset: " + t);
        }
        driver.view = null;
        driver.ready = false;
        try {
            LeiaSDK.shutdownSDK();
            Log.i(TAG, "CNSDK shut down with the stream");
        } catch (Throwable t) {
            Log.w(TAG, "CNSDK would not shut down: " + t);
        }
    }

    /**
     * Pins the window to full brightness while the panel is in 3D, and lets it go otherwise.
     *
     * In 3D the panel runs on the diffractive light alone, with the uniform backlight off, so
     * the same system brightness looks darker than it does in 2D. Adaptive brightness then
     * makes it worse without showing it: on this device it held the panel at 203 against a
     * system setting of 252. Tied to the 3D state rather than to the stream, so a side by side
     * stream watched flat does not sit at full brightness for no reason. It is a window
     * attribute, so it goes with the activity in any case.
     */
    private void applyBrightness(boolean threeD) {
        final Activity activity = activityRef == null ? null : activityRef.get();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                try {
                    android.view.WindowManager.LayoutParams params =
                            activity.getWindow().getAttributes();
                    float wanted = threeD ? 1.0f
                            : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    if (params.screenBrightness != wanted) {
                        params.screenBrightness = wanted;
                        activity.getWindow().setAttributes(params);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Could not set the window's brightness: " + t);
                }
            });
        }

        if (threeD) {
            brightnessPinned = true;
            askForBrightnessPermission(activity);
        } else if (brightnessPinned) {
            brightnessPinned = false;
            scheduleBrightnessRewrite();
        }
    }

    /**
     * Writes the system brightness again once the panel has left 3D.
     *
     * Releasing the window's hold is not enough on this panel. Android's own brightness comes
     * back at once -- measured, it is at the system level within a second -- but the panel
     * itself stays at full until something writes the system brightness again, which is why
     * moving the slider by hand fixed it. So this makes that write: one step up and back to
     * where it was, since writing an unchanged value is not passed on.
     *
     * It waits a second first, for the backlight to finish changing mode; a write that lands
     * while it is still switching is the one that gets lost. Without permission to modify
     * system settings it does nothing, and the panel behaves as it did before.
     */
    private static void scheduleBrightnessRewrite() {
        mainHandler.removeCallbacks(BRIGHTNESS_REWRITE);
        mainHandler.postDelayed(BRIGHTNESS_REWRITE, 1000);
    }

    private static final Runnable BRIGHTNESS_REWRITE = () -> {
        final Context context = appContext;
        if (context == null || !android.provider.Settings.System.canWrite(context)) {
            return;
        }
        final android.content.ContentResolver resolver = context.getContentResolver();
        final String key = android.provider.Settings.System.SCREEN_BRIGHTNESS;
        final int level = android.provider.Settings.System.getInt(resolver, key, -1);
        if (level < 0) {
            return;
        }
        try {
            android.provider.Settings.System.putInt(resolver, key, level < 255 ? level + 1 : level - 1);
            mainHandler.postDelayed(() -> {
                try {
                    android.provider.Settings.System.putInt(resolver, key, level);
                    Log.i(TAG, "Rewrote system brightness " + level + " after leaving 3D");
                } catch (Throwable t) {
                    Log.w(TAG, "Could not put the brightness back: " + t);
                }
            }, 300);
        } catch (Throwable t) {
            Log.w(TAG, "Could not rewrite the brightness: " + t);
        }
    };

    /**
     * Asks, once, for permission to modify system settings, saying what it is for.
     *
     * Only the brightness rewrite needs it, and 3D works without it; the cost of refusing is
     * that the screen can stay bright after a stream until the slider is moved. Asked on the
     * first move into 3D, where it is about to matter, and never again whatever the answer.
     */
    private static void askForBrightnessPermission(final Activity activity) {
        if (activity == null || android.provider.Settings.System.canWrite(activity)) {
            return;
        }
        final android.content.SharedPreferences prefs =
                android.preference.PreferenceManager.getDefaultSharedPreferences(activity);
        if (prefs.getBoolean(PREF_ASKED_BRIGHTNESS, false)) {
            return;
        }
        prefs.edit().putBoolean(PREF_ASKED_BRIGHTNESS, true).apply();

        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) {
                return;
            }
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(com.limelight.R.string.leia_brightness_permission_title)
                    .setMessage(com.limelight.R.string.leia_brightness_permission_message)
                    .setPositiveButton(com.limelight.R.string.leia_brightness_permission_open,
                            (dialog, which) -> {
                                try {
                                    activity.startActivity(new android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                            android.net.Uri.parse("package:" + activity.getPackageName())));
                                } catch (Throwable t) {
                                    Log.w(TAG, "Could not open the settings permission screen: " + t);
                                }
                            })
                    .setNegativeButton(com.limelight.R.string.leia_brightness_permission_later, null)
                    .show();
        });
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        this.hasFocus = hasFocus;
        applyState();
    }

    /**
     * A frame is only worth weaving when it actually holds two views, so the panel is told
     * to split the frame only while 3D is on.
     */
    private void applyTiling() {
        if (view == null) {
            return;
        }
        boolean split = sideBySide && enabled;
        try (InterlacedSurfaceViewConfigAccessor config = view.getConfig()) {
            config.setNumTiles(split ? 2 : 1, 1);
            // FIT_CENTER keeps each tile at its own shape, and a tile of a side by side frame
            // is not the shape of the scene: each eye was squeezed to half its width to share
            // the frame, so kept as it is it sits in the middle half of the panel with black
            // either side -- squashed, and dim for all the black. Split tiles are therefore
            // stretched to fill, which undoes the squeeze. Converted frames are already built
            // to the panel's shape, so for them fill and fit are the same thing. A single
            // tile is a frame shown flat, and keeps its proportions.
            config.setScaleType(split ? ScaleType.FILL : ScaleType.FIT_CENTER);
            com.leia.core.Vector2i size = config.getSourceSize();
            Log.i(TAG, "tiles=" + config.getNumTilesX() + "x" + config.getNumTilesY()
                    + " source=" + (size == null ? "null" : size.x + "x" + size.y)
                    + " scale=" + config.getScaleType()
                    + " view=" + view.getWidth() + "x" + view.getHeight());
        } catch (Throwable t) {
            Log.w(TAG, "Failed to set Leia tiling: " + t);
        }
    }

    /**
     * The backlight and the face tracker are shared with whatever else is on screen, so they
     * only run while this app is both in 3D and in front.
     */
    private void applyState() {
        if (!ready) {
            // Whatever is wanted here is applied again from didInitialize.
            return;
        }
        boolean active = enabled && hasFocus;
        // Order matters on the way out of 3D. Switching the backlight back to 2D while the
        // window still holds full brightness left the panel at full brightness after the app
        // had gone -- moving the system slider was what brought it back, which says whatever
        // took the level at the switch kept it. So the window lets go first, and the backlight
        // changes mode at the brightness it is about to live with. Going in, the backlight
        // switches first and the brightness follows.
        if (!active) {
            applyBrightness(false);
        }
        try {
            // enable, not start. Starting only runs a tracker that is already enabled, and
            // this app never enabled one: what came of that was a panel weaving from a
            // default viewpoint -- the frame carried its disparity, the log said nothing,
            // and it simply did not read as 3D. Enabling reports whether it took, so say so
            // when it does not rather than leaving it to be discovered by eye.
            boolean tracking = sdk.enableFaceTracking(active);
            if (active && !tracking) {
                Log.w(TAG, "The panel would not start tracking faces; 3D will look flat."
                        + " Another app holding the tracker is the usual reason.");
            }
            sdk.enableBacklight(active);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to set Leia panel state: " + t);
        }
        if (active) {
            applyBrightness(true);
        }
        applyTiling();
    }
}
