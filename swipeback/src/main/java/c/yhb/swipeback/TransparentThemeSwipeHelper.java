package c.yhb.swipeback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import c.yhb.util.ScreenUtils;

@RequiresApi(Build.VERSION_CODES.KITKAT)
public class TransparentThemeSwipeHelper implements SwipeBackActivity.SwipeBackHelper {

    private static final String TAG = "TransparentSwipeHelper";
    private static final int SHADOW_WIDTH = 30; //阴影宽度

    private AppCompatActivity mCurActivity, mPreActivity;
    private int mScreenWidth;
    private boolean mDragging;
    private boolean isTranslucentComplete; // window is in process of converting to transparent or reversely
    private boolean mAnimating;
    private ViewGroup mCurRootView, mPreRootView;
    private View mShadowView; // 阴影

    public TransparentThemeSwipeHelper(AppCompatActivity curActivity) {
        this.mCurActivity = curActivity;
        this.mScreenWidth = ScreenUtils.getScreenWidth(curActivity);
        convertActivityFromTranslucent(curActivity);
    }

    @Override
    public boolean handleTouchEvent(MotionEvent event) {
        float rawX = event.getRawX();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (mAnimating || mDragging) {
                    return false;
                }
                if (downInLeftEdge(event)) {
                    long time = System.currentTimeMillis();
                    readyDragging();
                    Log.d(TAG, getClass().getSimpleName() + ":readyDragging: " + (System.currentTimeMillis() - time) + " ms");
                    return true; // consumed and stop dispatching
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mAnimating) {
                    return false;
                }
                if (mDragging) {

                    Log.d(TAG, "handleTouchEvent: isTranslucentComplete=" + isTranslucentComplete);
                    if (!isTranslucentComplete) {
                        return false; // to avoid see the black behind the cur activity, allow swipe only after cur activity is completely transparent
                    }

                    if (!(Math.abs(mCurRootView.getX() - rawX) < 3)) {
                        draggingTo(rawX);
                    }
                    return true; // consumed and stop dispatching
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: // prevent from mess of second figure
            case MotionEvent.ACTION_UP:
                if (mDragging) {
                    long time = System.currentTimeMillis();
                    stopDraggingAt(rawX);
                    Log.d(TAG, getClass().getSimpleName() + ":stopDraggingAt: " + (System.currentTimeMillis() - time) + " ms");
                    return true; // consumed and stop dispatching
                }
                break;
        }

        return false; // don't care, please dispatch as usual
    }

    private void readyDragging() {
        mDragging = true;

        mPreActivity = (AppCompatActivity) SwipeBackSupport.getInstance().getPenultimateActivity(mCurActivity);

        if (mPreActivity == null || mPreActivity == mCurActivity) {
            mDragging = false;
            return;
        }

        ViewGroup curDecorView = (ViewGroup) mCurActivity.getWindow().getDecorView();
        ViewGroup preDecorView = (ViewGroup) mPreActivity.getWindow().getDecorView();

        mCurRootView = (ViewGroup) curDecorView.getChildAt(0);
        mPreRootView = (ViewGroup) preDecorView.getChildAt(0);

        mShadowView = new ShadowView(mCurActivity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(SHADOW_WIDTH, FrameLayout.LayoutParams.MATCH_PARENT);
        curDecorView.addView(mShadowView, params);
        mShadowView.setX(-SHADOW_WIDTH);

        mPreRootView.setX(-mScreenWidth / 3);

        convertActivityToTranslucent(mCurActivity);

        Log.d(TAG, "readyDragging: ");
    }

    private boolean downInLeftEdge(MotionEvent event) {
        return event.getRawX() < mScreenWidth / 15;
    }

    private void convertActivityToTranslucent(AppCompatActivity activity) {
        isTranslucentComplete = false;
        final long start = System.currentTimeMillis();

        try {
            Class translucentConversionListenerClass = null;
            Object callback = null;

            //获取透明转换回调类的class对象
            Class[] clazzArray = Activity.class.getDeclaredClasses();
            for (Class clazz : clazzArray) {
                if (clazz.getSimpleName().contains("TranslucentConversionListener")) {
                    translucentConversionListenerClass = clazz;
                }
            }

            //代理透明转换回调
            if (translucentConversionListenerClass != null) {
                InvocationHandler invocationHandler = new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        isTranslucentComplete = true;
                        Log.d(TAG, "convertActivityToTranslucent: " + (System.currentTimeMillis() - start) + "ms");
                        return null;
                    }
                };
                callback = Proxy.newProxyInstance(translucentConversionListenerClass.getClassLoader(), new Class[]{translucentConversionListenerClass}, invocationHandler);
            }

            //利用反射将窗口转为透明，注意 SDK21 及以上参数有所不同
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Object options = null;
                try {
                    @SuppressLint("PrivateApi")
                    Method getActivityOptions = Activity.class.getDeclaredMethod("getActivityOptions");
                    getActivityOptions.setAccessible(true);
                    options = getActivityOptions.invoke(this);
                } catch (Exception ignored) {
                }
                @SuppressLint("PrivateApi")
                Method convertToTranslucent = Activity.class.getDeclaredMethod("convertToTranslucent", translucentConversionListenerClass, ActivityOptions.class);
                convertToTranslucent.setAccessible(true);
                convertToTranslucent.invoke(activity, callback, options);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                @SuppressLint("PrivateApi")
                Method convertToTranslucent = Activity.class.getDeclaredMethod("convertToTranslucent", translucentConversionListenerClass);
                convertToTranslucent.setAccessible(true);
                convertToTranslucent.invoke(activity, callback);
            } else {
                Log.e(TAG, "convertActivityToTranslucent: invoked below api 19.");
            }

        } catch (Throwable ignored) {

        }

        //去除窗口背景
        activity.getWindow().setBackgroundDrawable(null);
    }

    private void convertActivityFromTranslucent(AppCompatActivity activity) {

        try {
            @SuppressLint("PrivateApi")
            Method convertFromTranslucent = Activity.class.getDeclaredMethod("convertFromTranslucent");
            convertFromTranslucent.setAccessible(true);
            convertFromTranslucent.invoke(activity);
        } catch (Exception ignore) {

        }

        Log.d(TAG, "convertActivityFromTranslucent: ");
    }

    private void draggingTo(float x) {
        mCurRootView.setX(x);
        mShadowView.setX(x - SHADOW_WIDTH);
        mPreRootView.setX(-mScreenWidth / 3 + x / 3);
        Log.d(TAG, "draggingTo: " + x);
    }

    private void stopDraggingAt(float x) {
        mDragging = false;
        boolean shouldFinishedCurActivity = x >= mScreenWidth / 3;
        if (shouldFinishedCurActivity) {
            animateToFinishCurActivity(x);
        } else {
            animateToRestoreEverything(x);
        }
        Log.d(TAG, "stopDraggingAt: ");
    }

    private void animateToFinishCurActivity(float from) {
        ValueAnimator animator = ValueAnimator.ofInt((int) from, mScreenWidth);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int x = (int) animation.getAnimatedValue();
                slidingTo(x);
            }
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishCurActivity();
                mAnimating = false;
            }
        });

        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
        mAnimating = true;
    }

    private void finishCurActivity() {
        mCurActivity.finish();
        mCurActivity.overridePendingTransition(0, 0);
    }

    private void animateToRestoreEverything(float from) {
        ValueAnimator animator = ValueAnimator.ofInt((int) from, 0);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int x = (int) animation.getAnimatedValue();
                slidingTo(x);
            }
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                restoreEverything();
                mAnimating = false;
            }
        });

        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
        mAnimating = true;
    }

    private void slidingTo(int x) {
        draggingTo(x);
    }

    private void restoreEverything() {
        convertActivityFromTranslucent(mCurActivity);
        ((ViewGroup) mCurRootView.getParent()).removeView(mShadowView);
        mPreRootView.setX(0);
        mDragging = false;
        mAnimating = false;
        isTranslucentComplete = false;
        mPreActivity = null;
    }

}
