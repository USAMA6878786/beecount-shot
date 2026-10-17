package dev.xtgxiso.beecountshot;

import android.content.Context;
import android.net.Uri;

/**
 * 用系统「分享」把截图交给蜜蜂记账，让它**自己**走完整的识别 + 落库流程。
 *
 * <h3>为什么需要它</h3>
 * 蜜蜂记账的截图识别入口有两类：
 * <ol>
 *   <li><b>自己发现</b>——MediaStore 观察者。它挂在界面上，界面被回收就废了；</li>
 *   <li><b>别人送进来</b>——{@code ACTION_SEND} 分享。这是它清单里注册过的入口
 *       （{@code action.SEND} + {@code image/*}），收到后会：
 *       复制图片到临时文件 → 调 Dart 的 {@code onImageShared} → 走它自己的识别流水线。</li>
 * </ol>
 *
 * <p>第 2 条路的好处是**零复刻**：分类匹配、账户匹配、币种汇率、去重、标签、周期账……
 * 全由它自己做，我们不碰它的数据库，它升级也不会把我们写崩。
 *
 * <h3>代价</h3>
 * 会把蜜蜂记账带到前台（约 2~4 秒）。这是这条路上唯一要接受的东西。
 *
 * <h3>为什么用 root 的 am start</h3>
 * Android 10+ 禁止后台应用直接启动 Activity。而磁贴点击时我们就在后台——
 * 用 root 跑 {@code am start} 不受这个限制，也不用担心 API 版本差异。
 */
final class ShareShot {

    /**
     * 把这张图分享给蜜蜂记账。
     *
     * @param uri 我们自己写进相册的那条记录（content:// 形式，带读权限授给宿主）
     * @return true = 命令发出去了
     */
    static boolean send(Context c, Uri uri) {
        if (uri == null) {
            Logx.w("[share] 没有可分享的 uri");
            return false;
        }
        try {
            String cmd = "am start -a android.intent.action.SEND"
                    + " -t image/png"
                    + " --eu android.intent.extra.STREAM '" + uri + "'"
                    + " --grant-read-uri-permission"
                    + " -p " + HostInfo.pkg()
                    + " 2>&1";
            RootShell.Result r = RootShell.exec(cmd, 15000L);
            boolean ok = r.exit == 0;
            Logx.i("[share] am start -> exit=" + r.exit + " out=" + RootShell.trim(r.out)
                    + " err=" + RootShell.trim(r.err));
            return ok;
        } catch (Throwable t) {
            Logx.e("[share] 分享失败", t);
            return false;
        }
    }

    /**
     * 过一会儿把这张图删掉。
     *
     * <p>分享模式下文件用的是**中性名字**（不含 screenshot 字样），所以蜜蜂记账的
     * 观察者不会认领它——也就不会出现"分享进去一次、观察者又认领一次"的重复记账。
     * 代价是它也不在我们的清理逻辑里，得自己删。
     */
    static void scheduleDelete(final String path) {
        if (path == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(30000L);
                } catch (InterruptedException ignored) {
                }
                // 中性文件名过不了 deleteFile 的"必须是截图"安全闸，这里直接 rm
                RootShell.Result r = RootShell.exec("rm -f '" + path + "'", 8000L);
                Logx.i("[share] 30 秒后清理分享用的截图 -> exit=" + r.exit
                        + " " + RootShell.baseName(path));
            }
        }, "bee-share-cleanup").start();
    }

    private ShareShot() {
    }
}
