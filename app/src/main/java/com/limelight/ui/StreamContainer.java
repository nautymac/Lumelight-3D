package com.limelight.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.PanelDriver;
import com.limelight.utils.Stereo3DRenderer;

/**
 * A container that manages different stream display modes and now correctly
 * handles all input callbacks, aspect ratio scaling, and a robust surface lifecycle.
 * It uses SurfaceView for 2D and GLSurfaceView for both 3D modes.
 */
public class StreamContainer extends FrameLayout implements SurfaceHolder.Callback, Stereo3DRenderer.OnSurfaceReadyListener {

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
        boolean handleFocusChange(boolean hasWindowFocus);
    }

    public enum StreamMode {
        MODE_2D,
        MODE_AI_3D,
        MODE_AI_3D_MOVIE
    }

    private Game game;
    private PreferenceConfiguration prefConfig;
    private Stereo3DRenderer mStereoRenderer;

    // Set only on a panel that weaves its own views, which then supplies mSurfaceView too.
    private PanelDriver mPanelDriver;
    // Runs the AI conversion off screen while the panel's own view shows the result.
    private GLSurfaceView mHiddenGlView;
    private int mPanelSurfaceWidth;
    private int mPanelSurfaceHeight;

    private SurfaceView mSurfaceView;
    private Surface mCurrentSurface;
    private Runnable onSurfaceAvailable;
    private StreamMode renderMode = null;
    private InputCallbacks mInputCallbacks;
    private boolean commitTextEnabled = false;

    private double desiredAspectRatio;
    private boolean fillDisplay = false;

    private boolean isSurfaceReady = false;

    public StreamContainer(Context context, AttributeSet attrs) {
        super(context, attrs);

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void init(Game game, PreferenceConfiguration prefConfig) {
        if (this.game != null) {
            return;
        }

        this.game = game;
        this.prefConfig = prefConfig;
        this.renderMode = mapIntToStreamMode(prefConfig.renderMode);

        Stereo3DRenderer.isMovieMode = renderMode == StreamMode.MODE_AI_3D_MOVIE;

        // These are static and outlive the stream, so clear them for the new one. Game only
        // sets an aspect ratio when the stream and the screen actually disagree, and a value
        // left over from a previous stream would box this one for no reason.
        Stereo3DRenderer.streamAspectRatio = 0;
        Stereo3DRenderer.fillDisplay = false;

        applyStereoOutputMode(prefConfig);

        isSurfaceReady = false;
        mCurrentSurface = null;

        Context context = getContext();
        LayoutParams childParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        // Always craete a surface view as a Workaround for the sizing issue of GLSurfaceView
        mSurfaceView = new SurfaceView(context);
        addView(mSurfaceView, childParams);

        // A panel that weaves its own views supplies the view that shows them, and takes the
        // stream straight into a surface of its own. Its SDK cannot be handed a frame this
        // app has already woven, so it takes precedence over the shader path below.
        //
        // The view it gives back is a SurfaceView, so it slots in where the plain one was
        // and the touch, zoom and sizing code carries on unchanged. What it does not do is
        // deliver its surface through SurfaceHolder: CNSDK owns the texture the stream lands
        // in and calls onPanelSurfaceReady instead, so this path skips the holder callback.
        // Only hand over to the panel when its 3D is actually wanted. Picking the glasses
        // output on this build means the panel stays out of it, and the frame has to go down
        // the ordinary path or it arrives side by side with nothing to pull it apart.
        if (mPanelDriver != null && mPanelDriver.ownsInterlacing() && prefConfig.stereoOutputMode != 0) {
            // When the conversion runs, the frame handed over holds both eyes at full width
            // rather than squeezed into one frame's worth. A half-width eye would reach the
            // panel as a 16:9 scene in a 4:3 tile, and the panel has no way to know it was
            // ever any other shape, so it would show it squeezed.
            boolean converting = renderMode != StreamMode.MODE_2D;
            if (converting) {
                // Each eye is shaped like the panel, not like the stream, and the stream is
                // centred inside it. The panel scales whatever it is handed to fill itself
                // whatever its scale type says, so a 16:9 stream would arrive stretched if
                // the bars were left for it to add.
                // The real metrics, not the resource ones: those describe the window with
                // the system bars taken out, and the panel's own shape is what the frame has
                // to match.
                //
                // They arrive in whichever way the screen is turned at this moment, and this
                // one is a portrait panel held sideways, so reading them as they come gives
                // 0.625 rather than 1.6 whenever the stream starts before the rotation has
                // settled. The two sides are therefore sorted and then matched to the way the
                // stream itself is shaped.
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                android.view.WindowManager wm =
                        (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                wm.getDefaultDisplay().getRealMetrics(metrics);
                int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
                int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
                double panelAspect;
                if (shortSide <= 0) {
                    panelAspect = (double) prefConfig.width / prefConfig.height;
                } else if (prefConfig.width >= prefConfig.height) {
                    panelAspect = (double) longSide / shortSide;
                } else {
                    panelAspect = (double) shortSide / longSide;
                }
                double streamAspect = (double) prefConfig.width / prefConfig.height;

                int eyeWidth, eyeHeight;
                if (streamAspect > panelAspect) {
                    eyeWidth = prefConfig.width;
                    eyeHeight = (int) Math.round(prefConfig.width / panelAspect);
                } else {
                    eyeHeight = prefConfig.height;
                    eyeWidth = (int) Math.round(prefConfig.height * panelAspect);
                }

                mPanelSurfaceWidth = eyeWidth * 2;
                mPanelSurfaceHeight = eyeHeight;
            } else {
                mPanelSurfaceWidth = prefConfig.width;
                mPanelSurfaceHeight = prefConfig.height;
            }

            View panelView = mPanelDriver.createStreamView(
                    context, mPanelSurfaceWidth, mPanelSurfaceHeight, this::onPanelSurfaceReady);
            if (panelView instanceof SurfaceView) {
                removeView(mSurfaceView);
                mSurfaceView = (SurfaceView) panelView;
                addView(mSurfaceView, childParams);

                if (renderMode != StreamMode.MODE_2D) {
                    // The stream is converted to 3D before the panel ever sees it, so the
                    // renderer still runs -- it just draws into the panel's surface rather
                    // than on screen. Its own view is only here to own the GL thread, so it
                    // is left a single pixel behind the panel's view.
                    GLSurfaceView glSurfaceView = new GLSurfaceView(context);
                    glSurfaceView.setEGLContextClientVersion(3);
                    mStereoRenderer = new Stereo3DRenderer(glSurfaceView, this, context, prefConfig);
                    glSurfaceView.setRenderer(mStereoRenderer);
                    glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                    addView(glSurfaceView, new LayoutParams(1, 1));
                    mHiddenGlView = glSurfaceView;

                    // What reaches the panel from here is always two eyes side by side,
                    // whatever the stream itself was, so it is told to expect that.
                    mPanelDriver.setStereoContent(true);
                }
                return;
            }
            // Falling through means CNSDK would not give us a view, so carry on without it.
            LimeLog.warning("Leia panel gave no usable view; falling back to the shader path");
        }

        if (renderMode != StreamMode.MODE_2D) {
            GLSurfaceView glSurfaceView = new GLSurfaceView(context);
            glSurfaceView.setEGLContextClientVersion(3);
            mStereoRenderer = new Stereo3DRenderer(glSurfaceView, this, context, prefConfig);
            glSurfaceView.setRenderer(mStereoRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            mSurfaceView = glSurfaceView;
            addView(mSurfaceView, childParams);
        }

        mSurfaceView.getHolder().addCallback(this);
        if (mSurfaceView.getHolder().getSurface() != null && mSurfaceView.getHolder().getSurface().isValid()) {
            surfaceChanged(mSurfaceView.getHolder(), PixelFormat.RGBA_8888, mSurfaceView.getWidth(), mSurfaceView.getHeight());
        }
    }

    // --- Aspect Ratio and Scaling Logic ---
    public void setDesiredAspectRatio(double aspectRatio) {
        this.desiredAspectRatio = aspectRatio;

        // onMeasure leaves the 3D modes alone, so the interlaced pass has to do its own
        // letterboxing inside the viewport.
        Stereo3DRenderer.streamAspectRatio = aspectRatio;
        requestLayout();
    }

    public void setFillDisplay(boolean fillDisplay) {
        this.fillDisplay = fillDisplay;
        Stereo3DRenderer.fillDisplay = fillDisplay;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (renderMode != StreamMode.MODE_2D) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        if (desiredAspectRatio == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int measuredHeight, measuredWidth;

        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(heightSize * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(widthSize / desiredAspectRatio);
            }
        } else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(measuredHeight * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(measuredWidth / desiredAspectRatio);
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY);
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY);
        measureChildren(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.mInputCallbacks = callbacks;
    }

    public void setCommitTextEnabled(boolean enabled) {
        this.commitTextEnabled = enabled;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (mInputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (mInputCallbacks.handleKeyDown(event)) return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mInputCallbacks.handleKeyUp(event)) return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mInputCallbacks != null) {
            mInputCallbacks.handleFocusChange(hasWindowFocus);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return commitTextEnabled || super.onCheckIsTextEditor();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (!commitTextEnabled) {
            return super.onCreateInputConnection(outAttrs);
        }
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                return mInputCallbacks != null && mInputCallbacks.handleCommitText(text) || super.commitText(text, newCursorPosition);
            }
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                return mInputCallbacks != null && mInputCallbacks.handleDeleteSurroundingText(beforeLength, afterLength) || super.deleteSurroundingText(beforeLength, afterLength);
            }
        };
    }

    public void setOnSurfaceAvailable(Runnable callback) {
        this.onSurfaceAvailable = callback;
        if (isSurfaceReady && onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
    }

    /**
     * The renderer's own view is off screen on a panel that shows the frame itself, so
     * nothing else drives its GL thread: without these it keeps running in the background
     * and comes back to a context it has already lost.
     */
    public void onResume() {
        if (mHiddenGlView != null) {
            mHiddenGlView.onResume();
        }
    }

    public void onPause() {
        if (mHiddenGlView != null) {
            mHiddenGlView.onPause();
        }
        // Let the converter go before the surface it draws into does. It blocks on its own
        // rendering thread, which is why this is not done on the way through here.
        if (mPanelDriver != null) {
            final PanelDriver driver = mPanelDriver;
            new Thread(driver::stopConversion, "leia-converter-release").start();
        }
    }

    public Surface getSurface() {
        return mCurrentSurface;
    }

    public SurfaceView getSurfaceView() {
        return mSurfaceView;
    }

    /**
     * Configures how the two eyes reach the screen: side by side for 3D glasses, or woven
     * into the screen columns for a glasses-free panel.
     *
     * The weave only resolves into depth if it matches how the lens sits on the panel, and
     * that alignment differs per device. Leia panels report it themselves, so those values
     * win; on everything else the user's calibration from the settings is used.
     */
    private void applyStereoOutputMode(PreferenceConfiguration prefConfig) {
        // The output format decides how to weave; whether the weave is on at the start of a
        // stream is the separate "Start Streams in 3D" setting, applied once the stream
        // actually connects. Until then the weave stays off, so the connection UI is readable
        // on a panel that would otherwise split it between the viewer's eyes.
        Stereo3DRenderer.isInterlaced = false;
        Stereo3DRenderer.interlaceNumViews = prefConfig.stereoOutputMode == 2 ? 4.0f : 2.0f;
        Stereo3DRenderer.interlaceViewOffset = prefConfig.interlaceViewOffset;
        Stereo3DRenderer.interlaceSwapEyes = prefConfig.interlaceSwapEyes ? 1.0f : 0.0f;

        // A panel that drives its own optics weaves the views itself, from where it has
        // tracked the viewer's face, so the shader must not weave them a second time. The
        // column settings above are meaningless on such a panel and are left unused.
        mPanelDriver = PanelDriver.get(getContext());
        boolean panelWeaves = mPanelDriver != null && mPanelDriver.ownsInterlacing();
        Stereo3DRenderer.panelOwnsInterlacing = panelWeaves;

        if (panelWeaves) {
            LimeLog.info("Panel drives its own interlacing; shader weave disabled");
            // Whether the frames are side by side is the 3D question on such a panel: the
            // output format list only describes shader weaves, which are not in use here.
            mPanelDriver.setStereoContent(prefConfig.stereoOutputMode != 0);
        }
    }

    public StreamMode mapIntToStreamMode(int modeIndex) {
        StreamContainer.StreamMode[] modes = StreamContainer.StreamMode.values();
        if (modeIndex >= 0 && modeIndex < modes.length) {
            return modes[modeIndex];
        } else {
            return StreamContainer.StreamMode.MODE_2D;
        }
    }

    private void notifySurfaceReady() {
        isSurfaceReady = true;
        if (onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
    }

    /**
     * The panel's SDK owns the texture the stream lands in, and calls this once it exists.
     * It arrives on the SDK's own thread, so the handover is posted to the main one.
     */
    private void onPanelSurfaceReady(Surface surface) {
        post(() -> {
            if (mStereoRenderer != null) {
                // The panel is told the size of the frame it will actually receive, which
                // either way is two views side by side.
                mPanelDriver.setSourceSize(mPanelSurfaceWidth, mPanelSurfaceHeight);

                // A panel that converts for itself does it better than the depth model here
                // can, on hardware meant for it, so it gets the surface and this side draws a
                // single view for it to work from. Its output is already two tiles, so what
                // reaches the panel is the same shape as before.
                boolean converted = mPanelDriver.startConversion(getContext(), surface,
                        mPanelSurfaceWidth / 2, mPanelSurfaceHeight,
                        prefConfig.parallax_depth / 0.5f,
                        (input, width, height) -> post(() -> {
                            if (mStereoRenderer != null) {
                                mStereoRenderer.setOutputSurface(input, width, height,
                                        prefConfig.width, prefConfig.height, true);
                            }
                        }));
                if (converted) {
                    return;
                }

                // Nothing to convert with, so the eyes are made here as before. The panel's
                // surface is where they go; what the stream is decoded into comes from the
                // renderer instead, and arrives through onStereo3DSurfaceReady.
                mStereoRenderer.setOutputSurface(surface, mPanelSurfaceWidth, mPanelSurfaceHeight,
                        prefConfig.width, prefConfig.height);
                return;
            }
            mCurrentSurface = surface;
            notifySurfaceReady();
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        game.surfaceCreated(holder);
    }
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (renderMode == StreamMode.MODE_2D && width > 0 && height > 0) {
            mCurrentSurface = holder.getSurface();
            notifySurfaceReady();
        }

        game.surfaceChanged(holder, format, width, height);
    }
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (renderMode == StreamMode.MODE_2D) {
            isSurfaceReady = false;
            mCurrentSurface = null;
        } else if (mStereoRenderer != null) {
            mStereoRenderer.onSurfaceDestroyed();
        }

        game.surfaceDestroyed(holder);
    }

    @Override
    public void onStereo3DSurfaceReady(Surface surface) {
        if (renderMode != StreamMode.MODE_2D) {
            mCurrentSurface = surface;
            notifySurfaceReady();
        }
    }

    public void onDestroy() {
        if (mStereoRenderer != null) {
            mStereoRenderer.onSurfaceDestroyed();
        }
    }
}
