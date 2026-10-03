package io.github.slightneko.notificationguard.hook;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.github.slightneko.notificationguard.data.ChannelKey;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

final class SystemUiHooks {
    private final GuardModule module;
    private final ClassLoader loader;
    private volatile Bridge bridge;
    private final ConcurrentHashMap<String, ChannelKey> channels = new ConcurrentHashMap<>();
    private final List<Object> revisions = new CopyOnWriteArrayList<>();
    private final AtomicLong revision = new AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean menuReady, iconsReady;
    SystemUiHooks(GuardModule module, ClassLoader loader) { this.module = module; this.loader = loader; }
    void install() throws Exception {
        try { trackEntries(); installIcons(); iconsReady = true; } catch (Throwable e) { module.error("Status bar channel filter unavailable",e); }
        try { installMenu(); menuReady = true; } catch (Throwable e) { module.error("Long-press menu unavailable",e); }
        Class<?> application = loader.loadClass("com.android.systemui.SystemUIApplication");
        module.attach(application.getDeclaredMethod("onCreate"), "ui-start", chain -> {
            Object result = chain.proceed();
            if (bridge == null) {
                try {
                    bridge = new Bridge((Application)chain.getThisObject(),module);
                    bridge.onRulesChanged = this::invalidate;
                    bridge.handler.postDelayed(this::tick,3000);
                } catch (Exception e) { module.error("SystemUI bridge initialization failed",e); }
            }
            return result;
        });
    }
    private void tick() {
        try { bridge.refreshRules(); bridge.heartbeat("systemui","长按菜单：" + (menuReady ? "已安装" : "不兼容") + "；状态栏渠道过滤：" + (iconsReady ? "已安装" : "不兼容")); }
        catch (Exception e) { module.error("SystemUI worker unavailable",e); }
        finally { bridge.handler.postDelayed(this::tick,5000); }
    }
    private void remember(Object value) {
        if (!(value instanceof StatusBarNotification sbn)) return;
        if (channels.size() > 8192) channels.clear();
        channels.put(sbn.getKey(),new ChannelKey(sbn.getUserId(),sbn.getPackageName(),sbn.getNotification().getChannelId()));
    }
    private void trackEntries() throws Exception {
        Class<?> entry = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry");
        for (Constructor<?> ctor : entry.getDeclaredConstructors()) module.attach(ctor,"entry-" + ctor.getParameterCount(),chain -> {
            Object result = chain.proceed();
            try { remember(Reflect.get(chain.getThisObject(),"mSbn")); } catch (Exception e) { module.error("Entry metadata unavailable",e); }
            return result;
        });
        for (Method method : entry.getDeclaredMethods()) {
            if (method.getName().equals("setSbn") && method.getParameterCount() == 1) module.attach(method,"entry-update",chain -> {
                Object result = chain.proceed(); remember(chain.getArg(0)); return result;
            });
        }
    }
    private void installIcons() throws Exception {
        Class<?> type = loader.loadClass("com.android.systemui.statusbar.notification.icon.domain.interactor.StatusBarNotificationIconsInteractor");
        Class<?> stateKt = loader.loadClass("kotlinx.coroutines.flow.StateFlowKt");
        Class<?> flowKt = loader.loadClass("kotlinx.coroutines.flow.FlowKt");
        Class<?> function = loader.loadClass("kotlin.jvm.functions.Function3");
        Reflect.field(type,"statusBarNotifs");
        for (Constructor<?> ctor : type.getDeclaredConstructors()) module.attach(ctor,"status-flow-" + ctor.getParameterCount(),chain -> {
            Object result = chain.proceed();
            try {
                Object instance = chain.getThisObject(); Object original = Reflect.get(instance,"statusBarNotifs");
                Object state = Reflect.callStatic(stateKt,"MutableStateFlow",0L);
                Object transform = Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,method,args) -> {
                    if (method.getName().equals("invoke")) return filter((Set<?>)args[0]);
                    if (method.getName().equals("toString")) return "NotificationGuardChannelFilter";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    throw new UnsupportedOperationException(method.getName());
                });
                Object combined = Reflect.callStatic(flowKt,"combine",original,state,transform);
                Reflect.field(type,"statusBarNotifs").set(instance,combined); revisions.add(state);
            } catch (Exception e) { iconsReady = false; module.error("Status bar flow wrapping failed",e); }
            return result;
        });
    }
    private Set<?> filter(Set<?> input) {
        Bridge b = bridge; if (b == null) return input;
        try {
            Set<Object> output = new LinkedHashSet<>();
            for (Object model : input) {
                ChannelKey key = channels.get((String)Reflect.get(model,"key"));
                if (key == null || !b.rule(key).hidden()) output.add(model);
            }
            return output;
        } catch (Exception e) { module.error("Icon filtering failed open",e); return input; }
    }
    private void invalidate() {
        long value = revision.incrementAndGet();
        for (Object state : revisions) try { Reflect.call(state,"setValue",value); } catch (Exception e) { module.error("Icon refresh unavailable",e); }
    }
    private void installMenu() throws Exception {
        Class<?> menu = loader.loadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow");
        Class<?> item = loader.loadClass("com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow$MiuiNotificationMenuItem");
        Constructor<?> ctor = null;
        for (Constructor<?> c : item.getDeclaredConstructors()) if (c.getParameterCount() == 4 && c.getParameterTypes()[0] == Context.class) ctor = c;
        if (ctor == null) throw new NoSuchMethodException("Menu item constructor"); ctor.setAccessible(true); final Constructor<?> factory = ctor;
        module.attach(menu.getDeclaredMethod("createMenuViews",boolean.class),"channel-menu",chain -> {
            Object result = chain.proceed();
            if (bridge == null) return result;
            try {
                Object row = chain.getThisObject();
                StatusBarNotification sbn = (StatusBarNotification) Reflect.get(row,"mSbn");
                if (sbn == null) return result;
                Context ctx = (Context)Reflect.get(row,"mContext");
                ChannelKey key = new ChannelKey(sbn.getUserId(),sbn.getPackageName(),sbn.getNotification().getChannelId()); remember(sbn);
                int titleId = resource(ctx,"miui_notification_menu_title_not_allow","string");
                int iconId = resource(ctx,"miui_notification_menu_ic_close","drawable");
                Object custom = factory.newInstance(ctx,titleId,null,iconId);
                Reflect.field(custom.getClass(),"mContentDescription").set(custom,"渠道拦截");
                View view = (View)Reflect.call(custom,"getMenuView");
                TextView title = view.findViewById(resource(ctx,"modal_menu_title","id")); title.setText("渠道拦截");
                View icon = (View)Reflect.get(custom,"mIcon"); icon.setContentDescription("渠道拦截与状态栏图标设置");
                String label = key.channel();
                try { Object entry = Reflect.call(Reflect.get(row,"mParent"),"getEntry"); Object channel = Reflect.call(entry,"getChannel"); label = String.valueOf(Reflect.call(channel,"getName")); } catch (Exception ignored) { }
                final String channelLabel = label;
                View.OnClickListener listener = v -> showRuleDialog(ctx,key,channelLabel);
                icon.setOnClickListener(listener); view.setOnClickListener(listener);
                @SuppressWarnings("unchecked") ArrayList<Object> items = (ArrayList<Object>)Reflect.get(row,"mMenuItems"); items.add(custom);
                ViewGroup container = (ViewGroup)Reflect.get(row,"mMenuContainer");
                int margin = (Integer)Reflect.get(row,"mMenuMargin");
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2,-2); params.leftMargin = margin; params.rightMargin = margin;
                container.addView(view,params);
                int available = ctx.getResources().getDisplayMetrics().widthPixels;
                int width = Math.max((int)(32 * ctx.getResources().getDisplayMetrics().density),available / items.size() - margin * 2);
                for (Object existing : items) {
                    View existingView = (View)Reflect.call(existing,"getMenuView");
                    TextView text = existingView.findViewById(resource(ctx,"modal_menu_title","id")); if (text != null) text.setMaxWidth(width);
                }
            } catch (Exception e) { module.error("Menu injection failed",e); }
            return result;
        });
    }
    private int resource(Context ctx,String name,String type) {
        int id = ctx.getResources().getIdentifier(name,type,"com.android.systemui");
        if (id == 0) throw new IllegalStateException("SystemUI resource missing: " + name); return id;
    }
    private void showRuleDialog(Context context,ChannelKey key,String label) {
        Bridge b = bridge; if (b == null) return;
        Bridge.Rule rule = b.rule(key); boolean[] selected = {rule.blocked(),rule.hidden()};
        AlertDialog dialog = new AlertDialog.Builder(context,android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label.isEmpty() ? "此通知渠道" : label)
                .setMultiChoiceItems(new String[]{"拦截此渠道","隐藏此渠道状态栏图标"},selected,(d,which,value) -> selected[which] = value)
                .setNegativeButton("取消",null)
                .setPositiveButton("应用",(d,which) -> b.writeRule(key,selected[0],selected[1],() -> main.post(() -> Toast.makeText(context,"渠道规则已更新",Toast.LENGTH_SHORT).show())))
                .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL); dialog.show();
    }
}
