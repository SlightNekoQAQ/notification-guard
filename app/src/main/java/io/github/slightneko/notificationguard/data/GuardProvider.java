package io.github.slightneko.notificationguard.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;

public final class GuardProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.slightneko.notificationguard.bridge";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private SQLiteDatabase db;

    @Override public boolean onCreate() {
        Context context = getContext().createDeviceProtectedStorageContext();
        db = new SQLiteOpenHelper(context, "guard.db", null, 1) {
            @Override public void onCreate(SQLiteDatabase d) {
                d.execSQL("CREATE TABLE rules(user INTEGER,pkg TEXT,channel TEXT,blocked INTEGER NOT NULL,hidden INTEGER NOT NULL,PRIMARY KEY(user,pkg,channel))");
                d.execSQL("CREATE TABLE channels(user INTEGER,pkg TEXT,channel TEXT,label TEXT,app TEXT,importance INTEGER,enabled INTEGER,PRIMARY KEY(user,pkg,channel))");
                d.execSQL("CREATE TABLE stats(day TEXT,user INTEGER,pkg TEXT,channel TEXT,attempts INTEGER NOT NULL,blocked INTEGER NOT NULL,updates INTEGER NOT NULL,PRIMARY KEY(day,user,pkg,channel))");
                d.execSQL("CREATE TABLE state(key TEXT PRIMARY KEY,value TEXT)");
                d.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,action TEXT,status TEXT,detail TEXT,created INTEGER)");
                d.execSQL("CREATE TABLE backup(user INTEGER,pkg TEXT,payload TEXT,PRIMARY KEY(user,pkg))");
            }
            @Override public void onUpgrade(SQLiteDatabase d, int old, int next) { throw new IllegalStateException("Unsupported database version"); }
        }.getWritableDatabase();
        return true;
    }

    private boolean own() { return Binder.getCallingUid() == Process.myUid(); }
    private void trusted() {
        int uid = Binder.getCallingUid();
        if (!own() && uid != Process.SYSTEM_UID) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null) for (String pkg : packages) if ("com.android.systemui".equals(pkg)) return;
            throw new SecurityException("Untrusted bridge caller");
        }
    }
    private void owner() { if (!own()) throw new SecurityException("Module-only operation"); }
    private void system() { if (Binder.getCallingUid() != Process.SYSTEM_UID) throw new SecurityException("System-only operation"); }
    private void changed() { getContext().getContentResolver().notifyChange(URI, null); }
    private static Bundle result(String json) { Bundle b = new Bundle(); b.putString("json", json); return b; }
    private JSONArray rows(String sql, String... args) throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) row.put(c.getColumnName(i), c.getLong(i));
                    else row.put(c.getColumnName(i), c.getString(i));
                }
                out.put(row);
            }
        }
        return out;
    }
    private void putState(String key, String value) {
        ContentValues v = new ContentValues(); v.put("key", key); v.put("value", value);
        db.insertWithOnConflict("state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static String[] identity(Bundle b) { return new String[] { Integer.toString(b.getInt("user")), b.getString("pkg", ""), b.getString("channel", "") }; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        trusted();
        Bundle b = extras == null ? new Bundle() : extras;
        try {
            switch (method) {
                case "rules": return result(rows("SELECT * FROM rules").toString());
                case "rule": {
                    String pkg = b.getString("pkg", "");
                    if (pkg.isEmpty() || pkg.length() > 255 || b.getString("channel", "").length() > 1000) throw new IllegalArgumentException("Invalid channel");
                    ContentValues v = new ContentValues(); v.put("user", b.getInt("user")); v.put("pkg", pkg); v.put("channel", b.getString("channel", ""));
                    v.put("blocked", b.getBoolean("blocked") ? 1 : 0); v.put("hidden", b.getBoolean("hidden") ? 1 : 0);
                    db.insertWithOnConflict("rules", null, v, SQLiteDatabase.CONFLICT_REPLACE); changed(); return Bundle.EMPTY;
                }
                case "stats": {
                    system(); JSONArray a = new JSONArray(b.getString("json", "[]"));
                    db.beginTransaction();
                    try {
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject r = a.getJSONObject(i);
                            Object[] ids = { r.getString("day"), r.getInt("user"), r.getString("pkg"), r.getString("channel") };
                            db.execSQL("INSERT OR IGNORE INTO stats VALUES(?,?,?,?,0,0,0)", ids);
                            db.execSQL("UPDATE stats SET attempts=attempts+?,blocked=blocked+?,updates=updates+? WHERE day=? AND user=? AND pkg=? AND channel=?", new Object[]{r.getInt("attempts"),r.getInt("blocked"),r.getInt("updates"),ids[0],ids[1],ids[2],ids[3]});
                        }
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    return Bundle.EMPTY;
                }
                case "heartbeat": {
                    String role = b.getString("role");
                    if (!"system".equals(role) && !"systemui".equals(role)) throw new IllegalArgumentException("Unknown role");
                    if ("system".equals(role)) system();
                    putState(role, Long.toString(System.currentTimeMillis()));
                    putState(role + "_detail", b.getString("detail", "")); return Bundle.EMPTY;
                }
                case "channels": {
                    system(); JSONArray a = new JSONArray(b.getString("json", "[]"));
                    db.beginTransaction();
                    try {
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject r = a.getJSONObject(i); ContentValues v = new ContentValues();
                            for (String field : new String[]{"user","importance","enabled"}) v.put(field, r.getInt(field));
                            for (String field : new String[]{"pkg","channel","label","app"}) v.put(field, r.getString(field));
                            db.insertWithOnConflict("channels", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                        }
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    return Bundle.EMPTY;
                }
                case "clearChannels": system(); db.delete("channels", null, null); return Bundle.EMPTY;
                case "request": {
                    owner();
                    if (!"refresh".equals(arg) && !"enable".equals(arg) && !"restore".equals(arg)) throw new IllegalArgumentException("Unknown action");
                    try (Cursor c = db.rawQuery("SELECT 1 FROM jobs WHERE status IN ('pending','running')", null)) { if (c.moveToFirst()) throw new IllegalStateException("已有任务正在执行"); }
                    ContentValues v = new ContentValues(); v.put("action", arg); v.put("status", "pending"); v.put("detail", "等待系统服务"); v.put("created", System.currentTimeMillis());
                    long id = db.insertOrThrow("jobs", null, v); changed(); Bundle out = new Bundle(); out.putLong("id", id); return out;
                }
                case "takeJob": {
                    system(); JSONArray a = rows("SELECT * FROM jobs WHERE status='pending' ORDER BY id LIMIT 1");
                    if (a.length() > 0) { db.execSQL("UPDATE jobs SET status='running',detail='执行中' WHERE id=?", new Object[]{a.getJSONObject(0).getLong("id")}); }
                    return result(a.toString());
                }
                case "jobResult": {
                    system(); db.execSQL("UPDATE jobs SET status=?,detail=? WHERE id=?", new Object[]{b.getString("status"),b.getString("detail"),b.getLong("id")}); changed(); return Bundle.EMPTY;
                }
                case "backupPut": {
                    system(); ContentValues v = new ContentValues(); v.put("user", b.getInt("user")); v.put("pkg", b.getString("pkg")); v.put("payload", b.getString("payload"));
                    db.insertWithOnConflict("backup", null, v, SQLiteDatabase.CONFLICT_IGNORE); return Bundle.EMPTY;
                }
                case "backupGet": system(); return result(rows("SELECT * FROM backup WHERE user=? AND pkg=?", Integer.toString(b.getInt("user")), b.getString("pkg")).toString());
                case "backupDelete": system(); db.delete("backup", "user=? AND pkg=?", new String[]{Integer.toString(b.getInt("user")), b.getString("pkg")}); return Bundle.EMPTY;
                case "backupIds": system(); return result(rows("SELECT user,pkg FROM backup").toString());
                case "clearStats": owner(); db.delete("stats", null, null); changed(); return Bundle.EMPTY;
                case "recoverJob": system(); db.execSQL("UPDATE jobs SET status='interrupted',detail='系统进程已重启；未自动继续，可重新执行或恢复备份' WHERE status='running'"); return Bundle.EMPTY;
                default: throw new IllegalArgumentException("Unknown operation");
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Bridge operation failed", e); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        owner(); String path = uri.getLastPathSegment(); Cursor c;
        if ("channels".equals(path)) c = db.rawQuery("SELECT c.*,COALESCE(r.blocked,0) blocked,COALESCE(r.hidden,0) hidden FROM channels c LEFT JOIN rules r USING(user,pkg,channel) ORDER BY c.app,c.user,c.label", null);
        else if ("rules".equals(path)) c = db.rawQuery("SELECT * FROM rules WHERE blocked=1 OR hidden=1 ORDER BY pkg,channel", null);
        else if ("state".equals(path)) c = db.rawQuery("SELECT * FROM state", null);
        else if ("jobs".equals(path)) c = db.rawQuery("SELECT * FROM jobs ORDER BY id DESC LIMIT 20", null);
        else if ("backup".equals(path)) c = db.rawQuery("SELECT COUNT(*) count FROM backup", null);
        else if ("stats".equals(path)) {
            String day = uri.getQueryParameter("since");
            c = db.rawQuery("SELECT s.user,s.pkg,s.channel,MAX(COALESCE(c.app,s.pkg)) app,MAX(COALESCE(c.label,s.channel)) label,SUM(s.attempts) attempts,SUM(s.blocked) blocked,SUM(s.updates) updates FROM stats s LEFT JOIN channels c USING(user,pkg,channel) WHERE s.day>=? GROUP BY s.user,s.pkg,s.channel ORDER BY attempts DESC", new String[]{day == null ? "0000-00-00" : day});
        } else throw new IllegalArgumentException("Unknown query");
        c.setNotificationUri(getContext().getContentResolver(), URI); return c;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/notificationguard"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] args) { throw new UnsupportedOperationException(); }
}
