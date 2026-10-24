package com.puppetlight.oneclick.shizuku;

// 运行在 Shizuku 启动的 shell(adb) 权限进程中
interface IShellService {
    // Shizuku 约定的销毁方法编号
    void destroy() = 16777114;

    // 执行 sh -c cmd，返回 stdout+stderr 的读取端（可以是持续输出，例如 getevent）
    ParcelFileDescriptor run(String cmd) = 1;

    // 注入触摸事件：xs/ys 为当前所有手指的坐标
    boolean injectMotion(long downTime, int action, in float[] xs, in float[] ys) = 2;

    // 注入按键（按下 + 抬起）
    boolean injectKey(int keyCode, int metaState) = 3;
}
