package io.github.slightneko.notificationguard.hook;

import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;

public final class GuardModule extends XposedModule {
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private SystemHooks system;
    private SystemUiHooks ui;
    void attach(Executable target, String id, XposedInterface.Hooker hooker) {
        handles.add(hook(target).setId(id).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker));
    }
    void error(String message, Throwable error) {
        // Do not log exception messages from Android: they can contain notification text.
        log(Log.ERROR, "NotificationGuard", message + " [" + error.getClass().getSimpleName() + "]");
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        try { system = new SystemHooks(this, param.getClassLoader()); system.install(); }
        catch (Throwable e) { error("System hooks unavailable", e); }
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName()) || ui != null) return;
        try { ui = new SystemUiHooks(this, param.getClassLoader()); ui.install(); }
        catch (Throwable e) { error("SystemUI hooks unavailable", e); }
    }
}
