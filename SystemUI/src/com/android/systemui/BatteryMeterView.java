package com.android.systemui;

import android.animation.ArgbEvaluator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;

import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryStateRegistar;
import com.android.systemui.statusbar.policy.BatteryStateRegistar.BatteryStateChangeCallback;
import com.qucii.systemui.statusbar.phone.StopMotionVectorDrawable;


public class BatteryMeterView extends View implements DemoMode, BatteryStateChangeCallback {
    public static final String TAG = BatteryMeterView.class.getSimpleName();
    public static final String ACTION_LEVEL_TEST = "com.android.systemui.BATTERY_LEVEL_TEST";
    public static final String SHOW_PERCENT_SETTING = "status_bar_show_battery_percent";
    private final int[] mColors;

    protected boolean mShowPercent = true;

    public enum BatteryMeterMode {
        BATTERY_METER_GONE,
        BATTERY_METER_ICON_PORTRAIT,
        BATTERY_METER_ICON_LANDSCAPE,
        BATTERY_METER_CIRCLE,
        BATTERY_METER_TEXT
    }

    private int mHeight;
    private int mWidth;
    private String mWarningString;
    private final int mCriticalLevel;

    private boolean mAnimationsEnabled;

    private BatteryStateRegistar mBatteryStateRegistar;
    private BatteryController mBatteryController;
    private boolean mPowerSaveEnabled;

    private int mDarkModeBackgroundColor;
    private int mDarkModeFillColor;

    private int mLightModeBackgroundColor;
    private int mLightModeFillColor;

    protected BatteryMeterMode mMeterMode = null;

    protected boolean mAttached;

    private boolean mDemoMode;
    protected BatteryTracker mDemoTracker = new BatteryTracker();
    protected BatteryTracker mTracker = new BatteryTracker();
    private BatteryMeterDrawable mBatteryMeterDrawable;
    private int mIconTint = Color.WHITE;

    private int mCurrentBackgroundColor = 0;
    private int mCurrentFillColor = 0;
    private int mChargeTint = Color.parseColor("#4cc79a");
    private int mCurrentIconTint = mIconTint;

    protected class BatteryTracker extends BroadcastReceiver {
        public static final int UNKNOWN_LEVEL = -1;

        // current battery status
        boolean present = true;
        int level = UNKNOWN_LEVEL;
        String percentStr;
        int plugType;
        boolean plugged;
        int health;
        int status;
        String technology;
        int voltage;
        int temperature;
        boolean testmode = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                if (testmode && ! intent.getBooleanExtra("testmode", false)) return;

                level = (int)(100f
                        * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                        / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));

                present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
                plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                plugged = plugType != 0;
                health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH,
                        BatteryManager.BATTERY_HEALTH_UNKNOWN);
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                setContentDescription(
                        context.getString(R.string.accessibility_battery_level, level));
                if (mBatteryMeterDrawable != null) {
                    setVisibility(View.VISIBLE);
                    invalidate();
                }
            } else if (action.equals(ACTION_LEVEL_TEST)) {
                testmode = true;
                post(new Runnable() {
                    int curLevel = 0;
                    int incr = 1;
                    int saveLevel = level;
                    int savePlugged = plugType;
                    Intent dummy = new Intent(Intent.ACTION_BATTERY_CHANGED);
                    @Override
                    public void run() {
                        if (curLevel < 0) {
                            testmode = false;
                            dummy.putExtra("level", saveLevel);
                            dummy.putExtra("plugged", savePlugged);
                            dummy.putExtra("testmode", false);
                        } else {
                            dummy.putExtra("level", curLevel);
                            dummy.putExtra("plugged", incr > 0
                                    ? BatteryManager.BATTERY_PLUGGED_AC : 0);
                            dummy.putExtra("testmode", true);
                        }
                        getContext().sendBroadcast(dummy);

                        if (!testmode) return;

                        curLevel += incr;
                        if (curLevel == 100) {
                            incr *= -1;
                        }
                        postDelayed(this, 200);
                    }
                });
            }
        }

    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ACTION_LEVEL_TEST);
        final Intent sticky = getContext().registerReceiver(mTracker, filter);
        if (sticky != null) {
            // preload the battery level
            mTracker.onReceive(getContext(), sticky);
        }
        if (mBatteryStateRegistar != null) {
            mBatteryStateRegistar.addStateChangedCallback(this);
        }
        mAttached = true;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mAttached = false;
        getContext().unregisterReceiver(mTracker);
        if (mBatteryStateRegistar != null) {
            mBatteryStateRegistar.removeStateChangedCallback(this);
        }
    }

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources res = context.getResources();
        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        mColors = new int[2*N];
        for (int i=0; i<N; i++) {
            mColors[2*i] = levels.getInt(i, 0);// 0,2 ---15,100
            mColors[2*i+1] = colors.getColor(i, 0);// 1,3 --saverMode 4cc79a
        }
        levels.recycle();
        colors.recycle();
        atts.recycle();
        mWarningString = context.getString(R.string.battery_meter_very_low_overlay_symbol);
        mCriticalLevel = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);

        mDarkModeBackgroundColor =
        	 context.getColor(R.color.dark_mode_icon_color_dual_tone_background);
        mDarkModeFillColor = context.getColor(R.color.dark_mode_icon_color_dual_tone_fill);
        mLightModeBackgroundColor =
                context.getColor(R.color.light_mode_icon_color_dual_tone_background);
        mLightModeFillColor = context.getColor(R.color.light_mode_icon_color_dual_tone_fill);

        setAnimationsEnabled(true);
    }

    protected BatteryMeterDrawable createBatteryMeterDrawable(BatteryMeterMode mode) {
        Resources res = getResources();
        switch (mode) {
            case BATTERY_METER_TEXT:
            case BATTERY_METER_GONE:
                return null;
            default:
                return new AllInOneBatteryMeterDrawable(res, mode);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = 0;

        if (mMeterMode == BatteryMeterMode.BATTERY_METER_ICON_LANDSCAPE) {
            Drawable  frame  = getContext().getDrawable(R.drawable.qucii_ic_battery_landscape_frame);
            height = frame.getIntrinsicHeight();
            width = frame.getIntrinsicWidth();
        }else {
            height = MeasureSpec.getSize(heightMeasureSpec);
            if (mMeterMode == BatteryMeterMode.BATTERY_METER_TEXT) {
                onSizeChanged(width, height, 0, 0); // Force a size changed event
            }
        }
        mHeight = height;
        setMeasuredDimension(width, height);
    }

    public void setBatteryStateRegistar(BatteryStateRegistar batteryStateRegistar) {
        mBatteryStateRegistar = batteryStateRegistar;
        if (!mAttached) {
            mBatteryStateRegistar.addStateChangedCallback(this);
        }
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mPowerSaveEnabled = mBatteryController.isPowerSave();
    }

    @Override
    public void onBatteryLevelChanged( int level, boolean pluggedIn,
            boolean charging) {
        // TODO: Use this callback instead of own broadcast receiver.
    }

    @Override
    public void onPowerSaveChanged() {
        mPowerSaveEnabled = mBatteryController.isPowerSave();
        invalidate();
    }

    public void setAnimationsEnabled(boolean enabled) {
        if (mAnimationsEnabled != enabled) {
            mAnimationsEnabled = enabled;
            setLayerType(mAnimationsEnabled ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE, null);
            invalidate();
        }
    }

    @Override
    public void onBatteryStyleChanged(int style, int percentMode) {
        boolean showInsidePercent = percentMode == BatteryController.PERCENTAGE_MODE_INSIDE;
        BatteryMeterMode meterMode = BatteryMeterMode.BATTERY_METER_ICON_PORTRAIT;

        switch (style) {
            case BatteryController.STYLE_CIRCLE:
                meterMode = BatteryMeterMode.BATTERY_METER_CIRCLE;
                break;
            case BatteryController.STYLE_GONE:
                meterMode = BatteryMeterMode.BATTERY_METER_GONE;
                showInsidePercent = false;
                break;
            case BatteryController.STYLE_ICON_LANDSCAPE:
                meterMode = BatteryMeterMode.BATTERY_METER_ICON_LANDSCAPE;
                break;
            case BatteryController.STYLE_TEXT:
                meterMode = BatteryMeterMode.BATTERY_METER_TEXT;
                showInsidePercent = false;
                break;
            default:
                break;
        }

        setMode(meterMode);
        // mBatteryMeterDrawable.onStyleChanged();
        mShowPercent = showInsidePercent;
        // if (null != mBatteryMeterDrawable) {
        //     mBatteryMeterDrawable.onStyleChanged();
        // }
        Log.e(TAG, "onBatteryStyleChanged");
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mHeight = h;
        mWidth = w;
        if (mBatteryMeterDrawable != null) {
            mBatteryMeterDrawable.onSizeChanged(w, h, oldw, oldh);
        }
    }

    public void setMode(BatteryMeterMode mode) {
        if (mMeterMode == mode) {
            return;
        }

        mMeterMode = mode;
        BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
        if (mode == BatteryMeterMode.BATTERY_METER_GONE ||
                mode == BatteryMeterMode.BATTERY_METER_TEXT) {
            setVisibility(View.GONE);
            mBatteryMeterDrawable = null;
        } else {
            if (mBatteryMeterDrawable != null) {
                mBatteryMeterDrawable.onDispose();
            }
            mBatteryMeterDrawable = createBatteryMeterDrawable(mode);
            if (tracker.present) {
                setVisibility(View.VISIBLE);
                requestLayout();
                invalidate();
            } else {
                setVisibility(View.GONE);
            }
        }
    }

    public int getColorForLevel(int percent) {
        // If we are in power save mode, always use the normal color.
        if (mPowerSaveEnabled) {
            return mColors[mColors.length-1];
        }
        int thresh, color = 0;
        for (int i=0; i<mColors.length; i+=2) {
            thresh = mColors[i];
            color = mColors[i+1];
            if (percent <= thresh) {

                // Respect tinting for "normal" level
                if (i == mColors.length-2) {
                    return mCurrentIconTint;
                } else {
                    return color;
                }
            }
        }
        return color;
    }

    public void setDarkIntensity(float darkIntensity) {
        if (mBatteryMeterDrawable != null) {
            int backgroundColor = getBackgroundColor(darkIntensity);
            mCurrentFillColor = getFillColor(darkIntensity);
            mBatteryMeterDrawable.setDarkIntensity(backgroundColor, mCurrentFillColor);
        }
    }

    private int getBackgroundColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeBackgroundColor, mDarkModeBackgroundColor);
    }

    private int getFillColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeFillColor, mDarkModeFillColor);
    }

    private int getColorForDarkIntensity(float darkIntensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mBatteryMeterDrawable != null) {
            BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
            mBatteryMeterDrawable.draw(canvas, tracker);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (getVisibility() == View.VISIBLE) {
            if (!mDemoMode && command.equals(COMMAND_ENTER)) {
                mDemoMode = true;
                mDemoTracker.level = mTracker.level;
                mDemoTracker.plugged = mTracker.plugged;
            } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
                mDemoMode = false;
                postInvalidate();
            } else if (mDemoMode && command.equals(COMMAND_BATTERY)) {
                String level = args.getString("level");
                String plugged = args.getString("plugged");
                if (level != null) {
                    mDemoTracker.level = Math.min(Math.max(Integer.parseInt(level), 0), 100);
                }
                if (plugged != null) {
                    mDemoTracker.plugged = Boolean.parseBoolean(plugged);
                }
                postInvalidate();
            }
        }
    }

    protected interface BatteryMeterDrawable {
        void draw(Canvas c, BatteryTracker tracker);
        void onSizeChanged(int w, int h, int oldw, int oldh);
        void onDispose();
        void setDarkIntensity(int backgroundColor, int fillColor);
        void onStyleChanged();
    }

    protected class AllInOneBatteryMeterDrawable  implements BatteryMeterDrawable {
        private static final boolean SINGLE_DIGIT_PERCENT = false;
        private static final boolean SHOW_100_PERCENT = false;

        private boolean mDisposed;

        private boolean mIsAnimating; // stores charge-animation status to remove callbacks

        private float mTextX, mTextY; // precalculated position for drawText() to appear centered

        private boolean mInitialized;

        private Paint mTextAndBoltPaint;
        private Paint mWarningTextPaint;
        private Paint mClearPaint;

        private LayerDrawable mBatteryDrawable;
        private Drawable mFrameDrawable;
        private StopMotionVectorDrawable mLevelDrawable;
        private Drawable mBoltDrawable;

        private int mTextGravity;

        public AllInOneBatteryMeterDrawable(Resources res, BatteryMeterMode mode) {
            super();

            loadBatteryDrawables(res, mode);

            mDisposed = false;

            // load text gravity and blend mode
            int[] attrs = new int[] {android.R.attr.gravity, R.attr.blendMode};
            int resId = getBatteryDrawableStyleResourceForMode(mode);
            PorterDuff.Mode xferMode = PorterDuff.Mode.XOR;
            if (resId != 0) {
                TypedArray a = getContext().obtainStyledAttributes(
                        getBatteryDrawableStyleResourceForMode(mode), attrs);
                mTextGravity = a.getInt(0, Gravity.CENTER);
                xferMode = PorterDuff.intToMode(a.getInt(1,
                        PorterDuff.modeToInt(PorterDuff.Mode.XOR)));
            } else {
                mTextGravity = Gravity.CENTER;
            }
            Log.d(TAG, "mTextGravity=" + mTextGravity);

            mTextAndBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            mTextAndBoltPaint.setTypeface(font);
            mTextAndBoltPaint.setTextAlign(getPaintAlignmentFromGravity(mTextGravity));
            mTextAndBoltPaint.setXfermode(new PorterDuffXfermode(xferMode));
            mTextAndBoltPaint.setColor(mCurrentFillColor != 0
                    ? mCurrentFillColor
                    : res.getColor(R.color.batterymeter_bolt_color));

            mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mWarningTextPaint.setColor(mColors[1]);
            font = Typeface.create("sans-serif", Typeface.BOLD);
            mWarningTextPaint.setTypeface(font);
            mWarningTextPaint.setTextAlign(getPaintAlignmentFromGravity(mTextGravity));

            mClearPaint = new Paint();
            mClearPaint.setColor(0);
        }

        @Override
        public void draw(Canvas c, BatteryTracker tracker) {
            if (mDisposed) return;

            if (!mInitialized) {
                init();
            }

            drawBattery(c, tracker);
            if (mAnimationsEnabled) {
                // TODO: Allow custom animations to be used
            }
        }

        @Override
        public void onDispose() {
            mDisposed = true;
        }

        @Override
        public void setDarkIntensity(int backgroundColor, int fillColor) {
            mIconTint = fillColor;
            // Make bolt fully opaque for increased visibility
            mBoltDrawable.setTint(0xff000000 | mIconTint);
            mCurrentBackgroundColor = 0xff000000 | backgroundColor;
            mFrameDrawable.setTint(mCurrentBackgroundColor);//透明度设为255
            updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);
            invalidate();
        }

        @Override
        public void onSizeChanged(int w, int h, int oldw, int oldh) {
            init();
        }

        private void checkBatteryMeterDrawableValid(Resources res, BatteryMeterMode mode) {
            final int resId = getBatteryDrawableResourceForMode(mode);
            final Drawable batteryDrawable;
            try {
                batteryDrawable = getContext().getDrawable(resId);
            } catch (Resources.NotFoundException e) {
                throw new BatteryMeterDrawableException(res.getResourceName(resId) + " is an " +
                        "invalid drawable", e);
            }

            // check that the drawable is a LayerDrawable
            if (!(batteryDrawable instanceof LayerDrawable)) {
                throw new BatteryMeterDrawableException("Expected a LayerDrawable but received a " +
                        batteryDrawable.getClass().getSimpleName());
            }

            final LayerDrawable layerDrawable = (LayerDrawable) batteryDrawable;
            final Drawable frame = layerDrawable.findDrawableByLayerId(R.id.battery_frame);
            final Drawable level = layerDrawable.findDrawableByLayerId(R.id.battery_fill);
            final Drawable bolt = layerDrawable.findDrawableByLayerId(
                    R.id.battery_charge_indicator);
            // now check that the required layers exist and are of the correct type
            Log.e(TAG, "bolt == " + bolt );
            if (frame == null) {
                throw new BatteryMeterDrawableException("Missing battery_frame drawble");
            }
            if (bolt == null) {
                throw new BatteryMeterDrawableException(
                        "Missing battery_charge_indicator drawable");
            }
            if (level != null) {
                // check that the level drawable is an AnimatedVectorDrawable
                if (!(level instanceof AnimatedVectorDrawable)) {
                    throw new BatteryMeterDrawableException("Expected a AnimatedVectorDrawable " +
                            "but received a " + level.getClass().getSimpleName());
                }
                // make sure we can stop motion animate the level drawable
                try {
                    StopMotionVectorDrawable smvd = new StopMotionVectorDrawable(level);
                    smvd.setCurrentFraction(0.5f);
                } catch (Exception e) {
                    throw new BatteryMeterDrawableException("Unable to perform stop motion on " +
                            "battery_fill drawable", e);
                }
            } else {
                throw new BatteryMeterDrawableException("Missing battery_fill drawable");
            }
        }

        private void loadBatteryDrawables(Resources res, BatteryMeterMode mode) {
            try {
                checkBatteryMeterDrawableValid(res, mode);
            } catch (BatteryMeterDrawableException e) {
                Log.i(TAG,"Invalid themed battery meter drawable, falling back to system",e);
            }
            int drawableResId = getBatteryDrawableResourceForMode(mode);
            mBatteryDrawable = (LayerDrawable) getContext().getDrawable(drawableResId);
            mFrameDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_frame);
            mFrameDrawable.setTint(0xffffffff);//初始化一个背景色
            // set the animated vector drawable we will be stop animating
            Drawable levelDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_fill);
            mLevelDrawable = new StopMotionVectorDrawable(levelDrawable);
            mBoltDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
        }

        // modified by mare for charge animtion begin 2017/01/10
        Handler mHandler  = new Handler();
        int mAnimOffset = 0;
        private int changingAnim (BatteryTracker tracker){
            int currentLevel = tracker.level;
            if (tracker.status != BatteryManager.BATTERY_STATUS_CHARGING){
                mAnimOffset = 0;
                mHandler.removeCallbacks(mInvalidate);
            } else {
                currentLevel += mAnimOffset;
                if (currentLevel >= 100){
                    currentLevel = 100;
                    mAnimOffset = 0;
                } else {
                    mAnimOffset += 10;
                }
                mHandler.removeCallbacks(mInvalidate);
                mHandler.postDelayed(mInvalidate,1000);
            }
            return currentLevel;
        }
        Runnable mInvalidate = new Runnable() {

            @Override
            public void run() {
                invalidate();
            }
        };

        //modified by mare for charge animtion end 2017/01/10

        private void drawBattery(Canvas canvas, BatteryTracker tracker) {
            boolean unknownStatus = tracker.status == BatteryManager.BATTERY_STATUS_UNKNOWN;
            int level = tracker.level;

            if (unknownStatus || tracker.status == BatteryManager.BATTERY_STATUS_FULL) {
                level = 100;
            }

            mTextAndBoltPaint.setColor(getColorForLevel(level));

            // Make sure we don't draw the charge indicator if not plugged in
            Drawable d = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
            if (d instanceof BitmapDrawable) {
                // In case we are using a BitmapDrawable, which we should be unless something bad
                // happened, we need to change the paint rather than the alpha in case the blendMode
                // has been set to clear.  Clear always clears regardless of alpha level ;)
                BitmapDrawable bd = (BitmapDrawable) d;
                bd.getPaint().set(tracker.plugged ? mTextAndBoltPaint : mClearPaint);
            } else {
                d.setAlpha(tracker.plugged ? 255 : 0);
            }
            // Now draw the level indicator
            // set the level and tint color of the fill drawable
            //modified by mare for charge animtion start 2017/01/10
            mLevelDrawable.setCurrentFraction(/*level */changingAnim(tracker)/ 100f);
            //modified by mare for charge animtion end 2017/01/10
            mCurrentIconTint = tracker.plugged ? mChargeTint : mIconTint;
            //#SM-2174,2986,2567,2145 add by mare for bettery charging animation begin 20170113
            int currentColor = tracker.plugged ? mChargeTint : getColorForLevel(level);
            mLevelDrawable.setTint(currentColor);
            //#SM-2174,2986,2567,2145 add by mare for bettery charging animation end 20170113
            mBatteryDrawable.draw(canvas);

            // if chosen by options, draw percentage text in the middle
            // always skip percentage when 100, so layout doesnt break
            if (unknownStatus) {
                mTextAndBoltPaint.setColor(getContext().getColor(R.color.batterymeter_frame_color));
                canvas.drawText("?", mTextX, mTextY, mTextAndBoltPaint);

            } else if (!tracker.plugged) {
                drawPercentageText(canvas, tracker);
            }
        }

        private void drawPercentageText(Canvas canvas, BatteryTracker tracker) {
            final int level = tracker.level;
            if (level > mCriticalLevel
                    && (mShowPercent && !(level == 100 && !SHOW_100_PERCENT))) {
                // draw the percentage text
                String pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level/10) : level);
                mTextAndBoltPaint.setColor(getColorForLevel(level));
                canvas.drawText(pctText, mTextX, mTextY, mTextAndBoltPaint);
            } else if (level <= mCriticalLevel) {
                // draw the warning text
                canvas.drawText(mWarningString, mTextX, mTextY, mWarningTextPaint);
            }
        }

        /**
         * initializes all size dependent variables
         */
        private void init() {
            // not much we can do with zero width or height, we'll get another pass later
            if (mWidth <= 0 || mHeight <=0) return;

            final float widthDiv2 = mWidth / 2f;
            final float heightDiv2 = mHeight / 2f;
            // text size is width / 2 - 2dp for wiggle room
            final float textSize = widthDiv2 - getResources().getDisplayMetrics().density * 2;
            mTextAndBoltPaint.setTextSize(textSize);
            mWarningTextPaint.setTextSize(textSize);

            int pLeft = getPaddingLeft();
            Rect iconBounds = new Rect(pLeft, 0, pLeft + mWidth, mHeight);
            mBatteryDrawable.setBounds(iconBounds);

            // calculate text position
            Rect bounds = new Rect();
            mTextAndBoltPaint.getTextBounds("99", 0, "99".length(), bounds);
            boolean isRtl = isLayoutRtl();

            // compute mTextX based on text gravity
            if ((mTextGravity & Gravity.START) == Gravity.START) {
                mTextX = isRtl ? mWidth : 0;
            } else if ((mTextGravity & Gravity.END) == Gravity.END) {
                mTextX = isRtl ? 0 : mWidth;
            } else if ((mTextGravity & Gravity.LEFT) == Gravity.LEFT) {
                mTextX = 0;
            }else if ((mTextGravity & Gravity.RIGHT) == Gravity.RIGHT) {
                mTextX = mWidth;
            } else {
                mTextX = widthDiv2 + pLeft;
            }

            // compute mTextY based on text gravity
            if ((mTextGravity & Gravity.TOP) == Gravity.TOP) {
                mTextY = bounds.height();
            } else if ((mTextGravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
                mTextY = mHeight;
            } else {
                mTextY = heightDiv2 + bounds.height() / 2.0f;
            }

            updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);

            mInitialized = true;
            Log.e(TAG, "init() performs....") ;
        }

        private int getBatteryDrawableResourceForMode(BatteryMeterMode mode) {
            switch (mode) {
                case BATTERY_METER_ICON_LANDSCAPE:
                    return R.drawable.qucii_ic_battery_landscape;
                case BATTERY_METER_CIRCLE:
                    return R.drawable.qucii_ic_battery_circle;
                case BATTERY_METER_ICON_PORTRAIT:
                    return R.drawable.qucii_ic_battery_portrait;
                default:
                    return 0;
            }
        }

        private int getBatteryDrawableStyleResourceForMode(BatteryMeterMode mode) {
            switch (mode) {
                case BATTERY_METER_ICON_LANDSCAPE:
                    return R.style.BatteryMeterViewDrawable_Landscape;
                case BATTERY_METER_CIRCLE:
                    return R.style.BatteryMeterViewDrawable_Circle;
                case BATTERY_METER_ICON_PORTRAIT:
                    return R.style.BatteryMeterViewDrawable_Portrait;
                default:
                    return R.style.BatteryMeterViewDrawable;
            }
        }

        private Paint.Align getPaintAlignmentFromGravity(int gravity) {
            boolean isRtl = isLayoutRtl();
            if ((gravity & Gravity.START) == Gravity.START) {
                return isRtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
            }
            if ((gravity & Gravity.END) == Gravity.END) {
                return isRtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
            }
            if ((gravity & Gravity.LEFT) == Gravity.LEFT) return Paint.Align.LEFT;
            if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) return Paint.Align.RIGHT;

            // default to center
            return Paint.Align.CENTER;
        }

        // Creates a BitmapDrawable of the bolt so we can make use of the XOR xfer mode with vector
        // based drawables
        private void updateBoltDrawableLayer(LayerDrawable batteryDrawable, Drawable boltDrawable) {
            BitmapDrawable newBoltDrawable;
            if (boltDrawable instanceof BitmapDrawable) {
                newBoltDrawable = (BitmapDrawable) boltDrawable.mutate();
            } else {
                Bitmap boltBitmap = createBoltBitmap(boltDrawable);
                if (boltBitmap == null) {
                    Log.i(TAG, "boltBitmap = null");
                    // not much to do with a null bitmap so keep original bolt for now
                    return;
                }
                Rect bounds = boltDrawable.getBounds();
                newBoltDrawable = new BitmapDrawable(getResources(), boltBitmap);
                newBoltDrawable.setBounds(bounds);
            }
            newBoltDrawable.getPaint().set(mTextAndBoltPaint);
            batteryDrawable.setDrawableByLayerId(R.id.battery_charge_indicator, newBoltDrawable);
//            batteryDrawable.setLayerInsetRelative(batteryDrawable.getNumberOfLayers() -1, 1,1,1,1);
        }

        private Bitmap createBoltBitmap(Drawable boltDrawable) {
            // not much we can do with zero width or height, we'll get another pass later
            if (mWidth <= 0 || mHeight <= 0) return null;

            Bitmap bolt;
            if (!(boltDrawable instanceof BitmapDrawable)) {
                int pLeft = getPaddingLeft();
                Rect iconBounds = new Rect(pLeft ,0, pLeft + mWidth,  mHeight);
                bolt = Bitmap.createBitmap(iconBounds.width(),iconBounds.height(), Bitmap.Config.ARGB_8888);
                if (bolt != null) {
                    Canvas c = new Canvas(bolt);
                    c.drawColor(-1, PorterDuff.Mode.CLEAR);
                    boltDrawable.draw(c);
                }
            } else {
                bolt = ((BitmapDrawable) boltDrawable).getBitmap();
            }
            Log.i(TAG, "createBoltBitmap >> bolt = " +bolt);
            return bolt;
        }

        private class BatteryMeterDrawableException extends RuntimeException {
            public BatteryMeterDrawableException(String detailMessage) {
                super(detailMessage);
            }

            public BatteryMeterDrawableException(String detailMessage, Throwable throwable) {
                super(detailMessage, throwable);
            }
        }

        @Override
        public void onStyleChanged() {
            mBoltDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
            updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);
            invalidate();
        }
    }

}
