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
        @Permission(alias = "audioOld", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        // Без POST_NOTIFICATIONS на Android 13+ шторка и экран блокировки
        // просто не покажутся: уведомление создастся и будет молча отброшено.
        @Permission(alias = "notify",   strings = { "android.permission.POST_NOTIFICATIONS" })
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

    /** Уведомления нужны только с API 33. Ниже — выдаются при установке. */
    @PluginMethod
    public void notifyAccess(PluginCall call) {
        JSObject r = new JSObject();
        if (Build.VERSION.SDK_INT < 33) { r.put("granted", true); call.resolve(r); return; }
        r.put("granted", getPermissionState("notify") == PermissionState.GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void requestNotify(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notify") == PermissionState.GRANTED) {
            JSObject r = new JSObject(); r.put("granted", true); call.resolve(r);
        } else {
            requestPermissionForAlias("notify", call, "afterNotify");
        }
    }

    @PermissionCallback
    private void afterNotify(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("notify") == PermissionState.GRANTED);
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

        // GENRE появился в MediaStore только на API 30. Без него радиостанции,
        // которые фильтруют библиотеку по жанру, не поймают ничего.
        boolean hasGenre = Build.VERSION.SDK_INT >= 30;

        java.util.List<String> cols = new java.util.ArrayList<>(java.util.Arrays.asList(
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
        ));
        if (hasGenre) cols.add(MediaStore.Audio.Media.GENRE);
        // Флаг «это музыка», а не рингтон/будильник/уведомление
        cols.add(MediaStore.Audio.Media.IS_MUSIC);
        // Папка: с API 29 есть RELATIVE_PATH, ниже — вытащим из DATA
        boolean hasRel = Build.VERSION.SDK_INT >= 29;
        cols.add(hasRel ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA);
        String[] proj = cols.toArray(new String[0]);

        // Запрашиваем ВСЁ и отсеиваем сами — так знаем точное число пропущенных
        // и можем отдать их по требованию. Никакого обхода диска: это выборка
        // из готового индекса, который системный MediaScanner уже построил
        // по всему устройству, включая карту памяти.
        final boolean includeAll = Boolean.TRUE.equals(call.getBoolean("includeAll", false));
        String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";
        int skipped = 0;
        java.util.Set<String> folders = new java.util.TreeSet<>();

        JSArray out = new JSArray();
        Uri artBase = Uri.parse("content://media/external/audio/albumart");
        // Ссылку на обложку MediaStore отдаёт для любого альбома, даже если
        // картинки нет — она просто не откроется. Раньше мы отдавали её всегда,
        // и дека считала, что обложка есть: поиск в сети такие треки пропускал.
        // Проверяем по-настоящему. Кешируем по альбому: ~40 проверок на 300 треков.
        java.util.HashMap<Long, Boolean> artOk = new java.util.HashMap<>();

        try (Cursor c = getContext().getContentResolver().query(col, proj, null, null, sort)) {
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
            // getColumnIndex, а не OrThrow: на старых прошивках колонки просто нет
            int iGen = hasGenre ? c.getColumnIndex(MediaStore.Audio.Media.GENRE) : -1;
            int iMus = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC);
            int iDir = c.getColumnIndex(hasRel ? MediaStore.Audio.Media.RELATIVE_PATH
                                               : MediaStore.Audio.Media.DATA);

            while (c.moveToNext()) {
                if (!includeAll && iMus >= 0 && c.getInt(iMus) == 0) { skipped++; continue; }

                long id  = c.getLong(iId);
                long aid = c.getLong(iAId);

                String dir = iDir >= 0 ? c.getString(iDir) : null;
                if (dir != null && !hasRel) {                 // из DATA берём каталог
                    int cut = dir.lastIndexOf('/');
                    dir = cut > 0 ? dir.substring(0, cut) : dir;
                }
                if (dir != null && !dir.isEmpty()) folders.add(dir);

                JSObject o = new JSObject();
                o.put("id",     String.valueOf(id));
                o.put("uri",    ContentUris.withAppendedId(col, id).toString());
                Boolean has = artOk.get(aid);
                if (has == null) {
                    try (java.io.InputStream in = getContext().getContentResolver()
                            .openInputStream(ContentUris.withAppendedId(artBase, aid))) {
                        has = in != null;
                    } catch (Exception e) { has = false; }
                    artOk.put(aid, has);
                }
                o.put("artUri", has ? ContentUris.withAppendedId(artBase, aid).toString() : "");
                o.put("title",  c.getString(iTit));
                o.put("artist", c.getString(iArt));
                o.put("album",  c.getString(iAlb));
                o.put("dur",    c.getLong(iDur) / 1000.0);
                o.put("size",   c.getLong(iSiz));
                o.put("mime",   c.getString(iMim));
                o.put("year",   c.getInt(iYr));
                o.put("no",     c.getInt(iTrk) % 1000);   // 1005 = диск 1, трек 5
                o.put("name",   c.getString(iNam));
                o.put("folder", dir == null ? "" : dir);
                String g = iGen >= 0 ? c.getString(iGen) : null;
                o.put("genre",  g == null ? "" : g);
                out.put(o);
            }
        } catch (Exception e) {
            call.reject("Сканирование не удалось: " + e.getMessage(), "SCAN_FAIL", e);
            return;
        }

        JSArray fl = new JSArray();
        for (String f : folders) fl.put(f);

        JSObject ret = new JSObject();
        ret.put("tracks", out);
        ret.put("count", out.length());
        ret.put("skipped", skipped);          // рингтоны, будильники, звук затвора
        ret.put("folders", fl);
        ret.put("folderCount", folders.size());
        call.resolve(ret);
    }
}
