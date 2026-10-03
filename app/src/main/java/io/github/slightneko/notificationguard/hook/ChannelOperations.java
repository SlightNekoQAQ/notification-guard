package io.github.slightneko.notificationguard.hook;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class ChannelOperations {
    private final Object api;
    private final Bridge bridge;
    private final List<String> errors = new ArrayList<>();
    private int success, failed;
    ChannelOperations(Object service, Bridge bridge) throws Exception { this.api = Reflect.get(service, "mService"); this.bridge = bridge; }
    private static String encode(Parcelable value) {
        Parcel p = Parcel.obtain(); try { value.writeToParcel(p, 0); return Base64.encodeToString(p.marshall(), Base64.NO_WRAP); } finally { p.recycle(); }
    }
    private static <T> T decode(String value, Parcelable.Creator<T> creator) {
        byte[] bytes = Base64.decode(value, Base64.NO_WRAP); Parcel p = Parcel.obtain();
        try { p.unmarshall(bytes, 0, bytes.length); p.setDataPosition(0); return creator.createFromParcel(p); } finally { p.recycle(); }
    }
    @SuppressWarnings("unchecked") private <T> List<T> list(String method, String pkg, int uid) throws Exception {
        Object slice = Reflect.call(api, method, pkg, uid, false);
        return (List<T>) Reflect.call(slice, "getList");
    }
    private Context userContext(int user) throws Exception {
        UserHandle handle = (UserHandle) Reflect.callStatic(UserHandle.class, "of", user);
        return (Context) Reflect.call(bridge.context, "createContextAsUser", handle, 0);
    }
    private List<Integer> users() throws Exception {
        UserManager manager = bridge.context.getSystemService(UserManager.class);
        List<?> list = (List<?>) Reflect.call(manager, "getUsers"); List<Integer> ids = new ArrayList<>();
        for (Object info : list) ids.add((Integer) Reflect.get(info, "id"));
        return ids;
    }
    private Bundle packageBundle(int user, String pkg) { Bundle b = new Bundle(); b.putInt("user",user); b.putString("pkg",pkg); return b; }
    private boolean enabled(String pkg, int uid) throws Exception { return (Boolean) Reflect.call(api, "areNotificationsEnabledForPackage", pkg, uid); }
    private Boolean media(String pkg, int uid) {
        try { return (Boolean) Reflect.call(api, "getNotificationsEnabledForMediaType", pkg, uid); }
        catch (Exception ignored) { return null; }
    }
    private void backup(int user, ApplicationInfo app) throws Exception {
        Bundle b = packageBundle(user, app.packageName);
        if (bridge.array("backupGet", b).length() != 0) return;
        JSONObject value = new JSONObject().put("enabled",enabled(app.packageName,app.uid));
        Boolean media = media(app.packageName,app.uid); if (media != null) value.put("media",media);
        JSONArray channels = new JSONArray(), groups = new JSONArray();
        for (NotificationChannel c : this.<NotificationChannel>list("getNotificationChannelsForPackage",app.packageName,app.uid)) channels.put(encode(c));
        for (NotificationChannelGroup g : this.<NotificationChannelGroup>list("getNotificationChannelGroupsForPackage",app.packageName,app.uid)) groups.put(encode(g));
        value.put("channels",channels).put("groups",groups);
        b.putString("payload",value.toString()); bridge.call("backupPut", null, b);
    }
    private void enable(int user, ApplicationInfo app) throws Exception {
        backup(user, app);
        String pkg = app.packageName; int uid = app.uid;
        Reflect.call(api, "setNotificationsEnabledForPackage",pkg,uid,true);
        if (!enabled(pkg,uid)) throw new IllegalStateException("notification permission remains disabled");
        Boolean media = media(pkg,uid);
        if (media != null && !media) {
            Reflect.call(api,"setNotificationsEnabledForMediaType",pkg,uid,true);
            if (!Boolean.TRUE.equals(media(pkg,uid))) throw new IllegalStateException("media switch remains disabled");
        }
        for (NotificationChannelGroup group : this.<NotificationChannelGroup>list("getNotificationChannelGroupsForPackage",pkg,uid)) {
            if (!group.isBlocked()) continue;
            Reflect.call(group,"setBlocked",false);
            Reflect.call(api,"updateNotificationChannelGroupForPackage",pkg,uid,group);
        }
        for (NotificationChannel channel : this.<NotificationChannel>list("getNotificationChannelsForPackage",pkg,uid)) {
            if (channel.getImportance() != 0) continue;
            int importance = 3;
            try { int original = (Integer) Reflect.call(channel,"getOriginalImportance"); if (original > 0 && original <= 5) importance = original; } catch (Exception ignored) { }
            channel.setImportance(importance);
            Reflect.call(api,"updateNotificationChannelForPackage",pkg,uid,channel);
        }
        for (NotificationChannelGroup g : this.<NotificationChannelGroup>list("getNotificationChannelGroupsForPackage",pkg,uid)) if (g.isBlocked()) throw new IllegalStateException("channel group remains blocked");
        for (NotificationChannel c : this.<NotificationChannel>list("getNotificationChannelsForPackage",pkg,uid)) if (c.getImportance() == 0) throw new IllegalStateException("channel remains blocked");
    }
    private void restore(int user, ApplicationInfo app) throws Exception {
        Bundle b = packageBundle(user,app.packageName); JSONArray backup = bridge.array("backupGet", b);
        if (backup.length() == 0) return;
        JSONObject value = new JSONObject(backup.getJSONObject(0).getString("payload"));
        JSONArray groups = value.getJSONArray("groups"), channels = value.getJSONArray("channels");
        for (int i = 0; i < groups.length(); i++) Reflect.call(api,"updateNotificationChannelGroupForPackage",app.packageName,app.uid,decode(groups.getString(i),NotificationChannelGroup.CREATOR));
        List<NotificationChannel> existing = list("getNotificationChannelsForPackage",app.packageName,app.uid);
        for (int i = 0; i < channels.length(); i++) {
            NotificationChannel channel = decode(channels.getString(i),NotificationChannel.CREATOR);
            if (existing.stream().noneMatch(c -> c.getId().equals(channel.getId()))) throw new IllegalStateException("a backed-up channel no longer exists");
            Reflect.call(api,"updateNotificationChannelForPackage",app.packageName,app.uid,channel);
            NotificationChannel actual = this.<NotificationChannel>list("getNotificationChannelsForPackage",app.packageName,app.uid).stream().filter(c -> c.getId().equals(channel.getId())).findFirst().orElseThrow();
            if (actual.getImportance() != channel.getImportance()) throw new IllegalStateException("channel restore rejected");
        }
        if (value.has("media")) Reflect.call(api,"setNotificationsEnabledForMediaType",app.packageName,app.uid,value.getBoolean("media"));
        Reflect.call(api,"setNotificationsEnabledForPackage",app.packageName,app.uid,value.getBoolean("enabled"));
        if (enabled(app.packageName,app.uid) != value.getBoolean("enabled")) throw new IllegalStateException("permission restore rejected");
        bridge.call("backupDelete",null,b);
    }
    private void publish(int user, ApplicationInfo app, PackageManager pm) throws Exception {
        List<NotificationChannel> list = list("getNotificationChannelsForPackage",app.packageName,app.uid);
        JSONArray rows = new JSONArray(); String label = pm.getApplicationLabel(app).toString();
        boolean enabled = enabled(app.packageName,app.uid);
        // Empty-channel rows keep apps with no created notification channels visible.
        if (list.isEmpty()) rows.put(row(user,app.packageName,"",label,"尚未创建渠道",-1,enabled));
        for (NotificationChannel c : list) {
            rows.put(row(user,app.packageName,c.getId(),label,String.valueOf(c.getName()),c.getImportance(),enabled));
            if (rows.length() >= 40) { sendRows(rows); rows = new JSONArray(); }
        }
        if (rows.length() != 0) sendRows(rows);
    }
    private JSONObject row(int user,String pkg,String channel,String app,String label,int importance,boolean enabled) throws Exception {
        return new JSONObject().put("user",user).put("pkg",pkg).put("channel",channel).put("app",app).put("label",label).put("importance",importance).put("enabled",enabled ? 1 : 0);
    }
    private void sendRows(JSONArray rows) { Bundle b = new Bundle(); b.putString("json",rows.toString()); bridge.call("channels",null,b); }
    private void failure(String pkg, int user, Exception e) {
        failed++; if (errors.size() < 80) errors.add(pkg + " [用户 " + user + "]: " + safeCause(e));
    }
    private String safeCause(Exception error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        // Only these module-authored messages are safe to expose. Framework messages are omitted.
        String message = cause.getMessage();
        if (cause instanceof IllegalStateException && message != null && (message.startsWith("notification permission") || message.startsWith("channel ") || message.startsWith("media switch") || message.startsWith("a backed-up") || message.startsWith("permission restore"))) return message;
        return cause.getClass().getSimpleName();
    }
    void run(JSONObject job) {
        long id = job.optLong("id"); String action = job.optString("action");
        try {
            if ("restore".equals(action)) {
                JSONArray ids = bridge.array("backupIds",null);
                for (int i = 0; i < ids.length(); i++) {
                    JSONObject row = ids.getJSONObject(i); int user = row.getInt("user"); String pkg = row.getString("pkg");
                    try { Context ctx = userContext(user); restore(user,ctx.getPackageManager().getApplicationInfo(pkg,0)); success++; }
                    catch (Exception e) { failure(pkg,user,e); }
                }
            } else {
                List<Integer> users = users();
                if ("refresh".equals(action)) bridge.call("clearChannels",null,null);
                for (int user : users) {
                    try {
                        Context ctx = userContext(user); PackageManager pm = ctx.getPackageManager();
                        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                            try { if ("enable".equals(action)) enable(user,app); publish(user,app,pm); success++; }
                            catch (Exception e) { failure(app.packageName,user,e); }
                        }
                    } catch (Exception e) { failure("用户空间",user,e); }
                }
            }
        } catch (Exception e) { failure("全局任务",0,e); }
        Bundle b = new Bundle(); b.putLong("id",id); b.putString("status",failed == 0 ? "done" : "partial");
        b.putString("detail","成功 " + success + "，失败 " + failed + (errors.isEmpty() ? "" : "\n" + String.join("\n",errors)));
        bridge.call("jobResult",null,b);
    }
}
