package io.github.slightneko.notificationguard.hook;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import io.github.slightneko.notificationguard.data.ChannelKey;
import io.github.slightneko.notificationguard.data.GuardProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

final class Bridge {
    record Rule(boolean blocked, boolean hidden) { }
    static final Rule ALLOW = new Rule(false, false);
    final Context context;
    final Handler handler;
    final GuardModule module;
    private volatile Map<ChannelKey, Rule> rules = Map.of();
    private String revision = "";
    Runnable onRulesChanged = () -> {};
    Bridge(Context context, GuardModule module) {
        this.context = context; this.module = module;
        HandlerThread thread = new HandlerThread("NotificationGuard"); thread.start(); handler = new Handler(thread.getLooper());
        context.getContentResolver().registerContentObserver(GuardProvider.URI, true, new ContentObserver(handler) {
            @Override public void onChange(boolean self) { refreshRules(); }
        });
    }
    Rule rule(ChannelKey key) { return rules.getOrDefault(key, ALLOW); }
    Bundle call(String method, String arg, Bundle b) {
        Bundle result = context.getContentResolver().call(GuardProvider.URI, method, arg, b);
        if (result == null) throw new IllegalStateException("Module bridge unavailable");
        return result;
    }
    JSONArray array(String method, Bundle b) throws Exception { return new JSONArray(call(method, null, b).getString("json", "[]")); }
    void refreshRules() {
        try {
            String json = call("rules", null, null).getString("json", "[]");
            if (revision.equals(json)) return;
            JSONArray a = new JSONArray(json); Map<ChannelKey, Rule> next = new HashMap<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                next.put(new ChannelKey(r.getInt("user"),r.getString("pkg"),r.getString("channel")), new Rule(r.getInt("blocked") != 0,r.getInt("hidden") != 0));
            }
            rules = Map.copyOf(next); revision = json; onRulesChanged.run();
        } catch (Exception e) { module.error("Rule refresh failed; retaining last known rules", e); }
    }
    void writeRule(ChannelKey key, boolean blocked, boolean hidden, Runnable success) {
        handler.post(() -> {
            try {
                Bundle b = keyBundle(key); b.putBoolean("blocked", blocked); b.putBoolean("hidden", hidden);
                call("rule", null, b); refreshRules(); success.run();
            } catch (Exception e) { module.error("Rule write failed", e); }
        });
    }
    void heartbeat(String role, String detail) {
        Bundle b = new Bundle(); b.putString("role", role); b.putString("detail", detail); call("heartbeat", null, b);
    }
    static Bundle keyBundle(ChannelKey key) {
        Bundle b = new Bundle(); b.putInt("user", key.user()); b.putString("pkg", key.pkg()); b.putString("channel", key.channel()); return b;
    }
}
