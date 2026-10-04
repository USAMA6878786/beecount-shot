package dev.xtgxiso.beecountshot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * 透明中转页——存在的唯一理由就是"让系统收起控制中心"。
 *
 * <p>AOSP 的 SystemUI `CustomTile.handleClick()` 里写得很明白：
 * <pre>
 *   if (mTile.getActivityLaunchForClick() != null) {
 *       startActivityAndCollapse(...);      // 只有这条会收起面板
 *   } else {
 *       mService.onClick(mToken);           // 普通 onClick 不会收起面板
 *   }
 * </pre>
 *
 * <p><b>v1.1 起这只是备选方案</b>：实测在部分定制 ROM 上 `startActivityAndCollapse` 并不生效，
 * 所以主用方案换成了 root 的 `cmd statusbar collapse`。这条路径保留给没有 root 的用户。
 *
 * <p>本 Activity 必须完全透明、无动画、不抢焦点、不进最近任务，并尽快 finish()，
 * 保证真正截屏时它已经消失。
 */
public class CollapseProxyActivity extends Activity {

    private static final long LINGER_MS = 140L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
        } catch (Throwable ignored) {
        }
        try {
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
        Logx.i("[proxy] collapse proxy shown (fallback path), finishing in " + LINGER_MS + "ms");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    finish();
                } catch (Throwable ignored) {
                }
            }
        }, LINGER_MS);
    }

    @Override
    public void finish() {
        super.finish();
        try {
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        try {
            finish();
        } catch (Throwable ignored) {
        }
    }
}
