package com.headqlink.link;

import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tu música del móvil para la pestaña «Mi música» de Radio: las canciones que Android tiene indexadas (MediaStore, las
 * de la carpeta Música, Descargas o un USB: MP3, FLAC, OGG, M4A…), por artista, álbum y número de pista. Suenan con el
 * reproductor de la radio (RadioPlayer), que sigue al cambiar de pantalla y responde a los mandos del volante.
 */
final class MusicLibrary {
    /** Como mucho tantas canciones. */
    static final int MAX = 5000;

    private MusicLibrary() {
    }

    /** Android 13+: el permiso de música y audio (antes no se pide: la app no tiene el de almacenamiento). */
    static boolean granted(Context c) {
        return Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Las canciones, como emisoras locales (Station.local); vacío sin permiso o sin música. Fuera del hilo principal. */
    static List<RadioPlayer.Station> query(Context c) {
        List<RadioPlayer.Station> out = new ArrayList<>();
        if (!granted(c)) return out;
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME};
        String where = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.DURATION + " >= 30000";
        String order = MediaStore.Audio.Media.ARTIST + " COLLATE NOCASE, " + MediaStore.Audio.Media.ALBUM + " COLLATE NOCASE, "
                + MediaStore.Audio.Media.TRACK;
        try (Cursor cur = c.getContentResolver().query(base, cols, where, null, order)) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < MAX) {
                long id = cur.getLong(0);
                RadioPlayer.Station s = new RadioPlayer.Station();
                s.local = true;
                s.name = title(cur.getString(1), cur.getString(6));
                s.group = artist(cur.getString(2));
                s.album = cur.getString(3) == null ? "" : cur.getString(3);
                s.url = ContentUris.withAppendedId(base, id).toString();
                s.logo = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), cur.getLong(4)).toString();
                s.durMs = cur.getLong(5);
                out.add(s);
            }
        } catch (RuntimeException e) {
            L.w("música: no se pudo leer la biblioteca (" + e.getClass().getSimpleName() + ")");
        }
        L.i("música: " + out.size() + " canciones");
        return out;
    }

    /** El título de la canción o, si no lo tiene, el nombre del fichero sin la extensión. */
    static String title(String title, String file) {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        String f = file == null ? "" : file;
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }

    /** El artista, sin el «<unknown>» que pone Android cuando el fichero no lo dice. */
    static String artist(String a) {
        if (a == null) return "";
        String t = a.trim();
        return t.equalsIgnoreCase("<unknown>") ? "" : t;
    }

    /** «Artista · 3:45» para la lista. */
    static String line(RadioPlayer.Station s) {
        String d = s.durMs > 0 ? String.format(Locale.ROOT, "%d:%02d", s.durMs / 60_000, (s.durMs / 1000) % 60) : "";
        if (s.group == null || s.group.isEmpty()) return d;
        return d.isEmpty() ? s.group : s.group + " · " + d;
    }
}
