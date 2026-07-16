package __PKG__;

import android.Manifest;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Читает индекс MediaStore — Android уже обошёл диск за нас.
 * Обхода файловой системы здесь нет и не нужно.
 */
@CapacitorPlugin(
    name = "MusicScanner",
    permissions = {
        // Два алиаса намеренно. Просить оба разом нельзя: на API 33+
        // READ_EXTERNAL_STORAGE молча не выдаётся и запрос виснет.
        @Permission(alias = "audio13",  strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "audioOld", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MusicScannerPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio13" : "audioOld";
    }

    @PluginMethod
    public void checkAccess(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState(alias()) == PermissionState.GRANTED);
        r.put("sdk", Build.VERSION.SDK_INT);
        call.resolve(r);
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "afterPermission");
        } else {
            doScan(call);
        }
    }

    @PermissionCallback
    private void afterPermission(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) doScan(call);
        else call.reject("Нет доступа к аудио", "DENIED");
    }

    private void doScan(PluginCall call) {
        Uri col = Build.VERSION.SDK_INT >= 29
            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] proj = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DISPLAY_NAME
        };

        // IS_MUSIC != 0 отсекает рингтоны, будильники и звук затвора камеры.
        String sel  = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        JSArray out = new JSArray();
        Uri artBase = Uri.parse("content://media/external/audio/albumart");

        try (Cursor c = getContext().getContentResolver().query(col, proj, sel, null, sort)) {
            if (c == null) { call.reject("MediaStore вернул null", "QUERY_NULL"); return; }

            int iId  = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int iTit = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int iArt = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int iAlb = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int iAId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int iSiz = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int iMim = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
            int iYr  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR);
            int iTrk = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);
            int iNam = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);

            while (c.moveToNext()) {
                long id  = c.getLong(iId);
                long aid = c.getLong(iAId);

                JSObject o = new JSObject();
                o.put("id",     String.valueOf(id));
                o.put("uri",    ContentUris.withAppendedId(col, id).toString());
                o.put("artUri", ContentUris.withAppendedId(artBase, aid).toString());
                o.put("title",  c.getString(iTit));
                o.put("artist", c.getString(iArt));
                o.put("album",  c.getString(iAlb));
                o.put("dur",    c.getLong(iDur) / 1000.0);
                o.put("size",   c.getLong(iSiz));
                o.put("mime",   c.getString(iMim));
                o.put("year",   c.getInt(iYr));
                o.put("no",     c.getInt(iTrk) % 1000);   // 1005 = диск 1, трек 5
                o.put("name",   c.getString(iNam));
                out.put(o);
            }
        } catch (Exception e) {
            call.reject("Сканирование не удалось: " + e.getMessage(), "SCAN_FAIL", e);
            return;
        }

        JSObject ret = new JSObject();
        ret.put("tracks", out);
        ret.put("count", out.length());
        call.resolve(ret);
    }
}
