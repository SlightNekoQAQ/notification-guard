package io.github.slightneko.notificationguard.hook;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import io.github.slightneko.notificationguard.data.ChannelKey;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

final class SystemHooks {
    private final GuardModule module;
    private final ClassLoader loader;
    private volatile Bridge bridge;
    private Object service;
    private final ThreadLocal<Attempt> current = new ThreadLocal<>();
    private final Map<DailyKey, Counts> pending = new HashMap<>();
    private String detail = "";
    private record DailyKey(String day, ChannelKey key) { }
    private static final class Counts { int attempts, blocked, updates; }
    private static final class Attempt {
        ChannelKey key; boolean blocked, update;
        Attempt(ChannelKey key) { this.key = key; }
    }
    SystemHooks(GuardModule module, ClassLoader loader) { this.module = module; this.loader = loader; }
    void install() throws Exception {
        Class<?> cls = loader.loadClass("com.android.server.notification.NotificationManagerService");
        Method entry = null, gate = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals("enqueueNotificationInternal") && m.getReturnType() == boolean.class
                    && m.getParameterCount() >= 10 && m.getParameterTypes()[6] == Notification.class) {
                if (entry != null) throw new IllegalStateException("Ambiguous enqueue entry"); entry = m;
            }
            if (m.getName().equals("checkDisqualifyingFeatures") && m.getReturnType() == boolean.class
                    && m.getParameterCount() == 7 && m.getParameterTypes()[4].getName().endsWith("NotificationRecord")) gate = m;
        }
        if (entry == null || gate == null) throw new NoSuchMethodException("Verified notification hook signatures not present");
        module.attach(entry, "attempt", chain -> {
            Bridge b = bridge;
            if (b == null || current.get() != null) return chain.proceed();
            Notification n = (Notification) chain.getArg(6);
            String pkg = (String) chain.getArg(0);
            int user = (Integer) chain.getArg(7);
            boolean appProvided = (Boolean) chain.getArg(chain.getArgs().size() - 1);
            if (n == null || pkg == null || !appProvided || user < 0) return chain.proceed();
            Attempt a = new Attempt(new ChannelKey(user, pkg, n.getChannelId())); current.set(a);
            boolean completed = false;
            try { Object result = chain.proceed(); completed = true; return result; }
            finally { current.remove(); if (completed) record(a); }
        });
        module.attach(gate, "gate", chain -> {
            Object allowed = chain.proceed();
            Attempt a = current.get(); Bridge b = bridge;
            if (a == null || b == null) return allowed;
            try {
                StatusBarNotification sbn = (StatusBarNotification) Reflect.call(chain.getArg(4), "getSbn");
                a.key = new ChannelKey(sbn.getUserId(), sbn.getPackageName(), sbn.getNotification().getChannelId());
                Object lock = Reflect.get(chain.getThisObject(), "mNotificationLock");
                synchronized (lock) { a.update = ((Map<?,?>) Reflect.get(chain.getThisObject(), "mNotificationsByKey")).containsKey(sbn.getKey()); }
                if (Boolean.TRUE.equals(allowed) && b.rule(a.key).blocked()) { a.blocked = true; return false; }
            } catch (Exception e) { module.error("Notification gate failed open", e); }
            return allowed;
        });
        Method start = cls.getDeclaredMethod("onStart");
        module.attach(start, "service-start", chain -> {
            Object result = chain.proceed();
            service = chain.getThisObject();
            try {
                Context context = (Context) Reflect.call(service, "getContext");
                bridge = new Bridge(context, module);
                detail = "发送计数与渠道拦截 Hook 已安装";
                bridge.onRulesChanged = this::cancelBlocked;
                bridge.handler.postDelayed(() -> { try { bridge.call("recoverJob", null, null); } catch (Exception e) { module.error("Job recovery unavailable", e); } tick(); }, 15000);
            } catch (Exception e) { module.error("System bridge initialization failed", e); }
            return result;
        });
        module.deoptimize(entry);
    }
    private void record(Attempt a) {
        DailyKey key = new DailyKey(LocalDate.now().toString(), a.key);
        synchronized (pending) {
            if (pending.size() > 10000 && !pending.containsKey(key)) return;
            Counts count = pending.computeIfAbsent(key, k -> new Counts());
            count.attempts++; if (a.blocked) count.blocked++; if (a.update) count.updates++;
        }
    }
    private void flush() throws Exception {
        // Hold only the metadata counter lock; no Android notification lock is held during IPC.
        Map<DailyKey, Counts> batch;
        synchronized (pending) { batch = new HashMap<>(pending); pending.clear(); }
        if (batch.isEmpty()) return;
        try {
            JSONArray array = new JSONArray();
            for (Map.Entry<DailyKey, Counts> row : batch.entrySet()) {
                DailyKey k = row.getKey(); Counts c = row.getValue();
                array.put(new JSONObject().put("day",k.day()).put("user",k.key().user()).put("pkg",k.key().pkg()).put("channel",k.key().channel()).put("attempts",c.attempts).put("blocked",c.blocked).put("updates",c.updates));
            }
            Bundle b = new Bundle(); b.putString("json", array.toString()); bridge.call("stats", null, b);
        } catch (Exception e) {
            synchronized (pending) {
                for (var row : batch.entrySet()) { Counts c = pending.computeIfAbsent(row.getKey(), k -> new Counts()); c.attempts += row.getValue().attempts; c.blocked += row.getValue().blocked; c.updates += row.getValue().updates; }
            }
            throw e;
        }
    }
    private void tick() {
        try {
            bridge.refreshRules(); flush(); bridge.heartbeat("system", detail);
            JSONArray jobs = bridge.array("takeJob", null);
            if (jobs.length() != 0) new ChannelOperations(service, bridge).run(jobs.getJSONObject(0));
        } catch (Exception e) { module.error("System worker unavailable", e); }
        finally { bridge.handler.postDelayed(this::tick, 5000); }
    }
    private void cancelBlocked() {
        try {
            Object lock = Reflect.get(service, "mNotificationLock");
            java.util.Set<ChannelKey> keys = new java.util.HashSet<>();
            synchronized (lock) {
                for (Object record : (java.util.List<?>) Reflect.get(service, "mNotificationList")) {
                    StatusBarNotification sbn = (StatusBarNotification) Reflect.call(record, "getSbn");
                    ChannelKey key = new ChannelKey(sbn.getUserId(),sbn.getPackageName(),sbn.getNotification().getChannelId());
                    if (bridge.rule(key).blocked()) keys.add(key);
                }
            }
            for (ChannelKey key : keys) Reflect.call(service, "cancelAllNotificationsInt", Process.SYSTEM_UID, Process.myPid(), key.pkg(), key.channel(), 0, Notification.FLAG_FOREGROUND_SERVICE, key.user(), 17);
        } catch (Exception e) { module.error("Existing notification removal unavailable", e); }
    }
}
