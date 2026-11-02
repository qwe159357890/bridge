package top.wherewego.vnt.jni;

import java.io.Closeable;
import java.io.IOException;

/**
 * vnt的Java映射
 *
 * @author https://github.com/lbl8603/vnt
 */
public class Vnt implements Closeable {
    static {
        // v0.2.32：官方 v1.2.9 的 14 个绑定类里没有任何 System.loadLibrary（原 App 在别处加载）。
        // Bridge 独立集成必须在此补上，否则 6 个 native 方法全部 UnsatisfiedLinkError。
        // 库名 libvnt_jni.so，ABI 仅 arm64-v8a / x86_64（官方 1.x 从未提供 armeabi-v7a）。
        System.loadLibrary("vnt_jni");
    }

    private final long raw;

    public Vnt(Config config, CallBack callBack) throws Exception {
        this.raw = new0(config, callBack);
        if (this.raw == 0) {
            throw new RuntimeException();
        }
    }

    public void stop() {
        stop0(raw);
    }

    public void await() {
        wait0(raw);
    }

    public boolean awaitTimeout(long ms) {
        return waitTimeout0(raw, ms);
    }

    public PeerRouteInfo[] list() {
        return list0(raw);
    }

    private native long new0(Config config, CallBack callBack) throws Exception;

    private native void stop0(long raw);

    private native void wait0(long raw);

    private native boolean waitTimeout0(long raw, long ms);

    private native void drop0(long raw);

    private native PeerRouteInfo[] list0(long raw);

    @Override
    public void close() throws IOException {
        drop0(raw);
    }
}
