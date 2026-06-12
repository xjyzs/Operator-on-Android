package com.xjyzs.operator;

interface IInputControl {
    boolean ping();

    void downSync(int x, int y, int displayId);
    void moveSync(int x, int y, int displayId);
    void upSync(int x, int y, int displayId);

    int createVirtualDisplay(in Surface surface, int width, int height, int densityDpi);
    void setVirtualDisplaySurface(int displayId, in Surface surface);
    void moveAppToDisplay(String packageName, int displayId);
    // 使用物理屏时，需要设置屏幕尺寸
    void setSize(int width, int height);
    ParcelFileDescriptor captureScreen(int displayId,float x1, float y1, float x2, float y2);
    /** 释放虚拟屏 */
    void releaseVirtualDisplay(int displayId);

    void exit();
}