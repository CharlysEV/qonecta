package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Radio por internet y tu música del móvil (MusicLibrary): un único reproductor que sigue sonando al salir de la
 * pantalla Radio (como la música de AA) y se para al cerrar la interfaz propia. Pide el foco de audio, así que pausa la
 * música de AA y se pausa si AA (o una llamada) lo toma. Una canción, al acabar, da paso a la siguiente. Solo hilo
 * principal.
 */
final class RadioPlayer {
    static final class Station {
        String name;
        String url;
        String logo;
        /** Emisora: sus etiquetas o el grupo de la lista; canción: el artista. */
        String group;
        /** Una canción del móvil (no una emisora): se reanuda donde iba y al acabar suena la siguiente. */
        boolean local;
        String album = "";
        long durMs;
    }

    private static ExoPlayer player;
    private static MediaFocus mediaFocus;
    private static PlaybackMetadata metadata;
    private static final List<Station> list = new ArrayList<>();
    private static int index = -1;
    /** Título que emite la emisora (ICY StreamTitle), o "" si no manda. */
    static String nowTitle = "";
    static String error = "";
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private RadioPlayer() {
    }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void changed() {
        for (Runnable r : listeners) r.run();
    }

    static Station current() {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    static boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    static boolean isBuffering() {
        return player != null && player.getPlaybackState() == Player.STATE_BUFFERING;
    }

    static void play(Context ctx, List<Station> stations, int i) {
        list.clear();
        list.addAll(stations);
        index = i;
        start(ctx.getApplicationContext());
    }

    private static void start(Context app) {
        Station s = current();
        if (s == null) return;
        if (player == null) {
            // Con FFmpeg de reserva: FLAC, OGG u Opus que el móvil no sepa decodificar también suenan.
            player = VideoPlayer.newPlayer(app);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
            mediaFocus = MediaFocus.bind(app, player);
            player.addListener(new Player.Listener() {
                @Override
                public void onMediaMetadataChanged(MediaMetadata m) {
                    // Una canción ya trae su título y artista de la biblioteca; lo que emite una emisora, de aquí.
                    Station st = current();
                    if (st != null && st.local) return;
                    nowTitle = m.title != null ? m.title.toString() : "";
                    publishMetadata();
                    changed();
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    error = Str.get(R.string.hql_cannot_play, e.getErrorCodeName());
                    Station st = current();
                    L.w("radio: " + (st != null ? st.name : "?") + ": " + e);
                    changed();
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    // Canción terminada: la siguiente de la lista.
                    Station st = current();
                    if (state == Player.STATE_ENDED && st != null && st.local && list.size() > 1) {
                        step(app, 1);
                        return;
                    }
                    changed();
                }

                @Override
                public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                    changed();
                }
            });
            metadata = new PlaybackMetadata(app, player, "Radio", RadioPlayer::resume,
                    RadioPlayer::stop, () -> step(app, -1), () -> step(app, 1));
        }
        // Con la pantalla del móvil apagada sigue sonando: CPU (y, la radio, la Wi-Fi) despiertos mientras suena.
        player.setWakeMode(s.local ? C.WAKE_MODE_LOCAL : C.WAKE_MODE_NETWORK);
        nowTitle = s.local ? s.group : "";
        error = "";
        publishMetadata();
        player.setMediaItem(new MediaItem.Builder().setUri(s.url)
                .setMediaMetadata(new MediaMetadata.Builder().setStation(s.name).build()).build());
        player.prepare();
        player.play();
        L.i(s.local ? "música: canción " + (index + 1) + " de " + list.size() : "radio: " + s.name + " · " + s.url);
        changed();
    }

    /** Solo la vista previa: una emisora "sonando" sin reproducir nada. */
    static void previewOnly(Station s) {
        list.clear();
        list.add(s);
        index = 0;
        changed();
    }

    static void toggle() {
        if (player == null) return;
        if (player.getPlayWhenReady()) {
            player.pause();
        } else {
            resume();
        }
        changed();
    }

    private static void resume() {
        if (player == null) return;
        Station st = current();
        if (st != null && st.local) {
            // Una canción sigue donde iba (y si había terminado, desde el principio).
            if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
            player.play();
            return;
        }
        // Radio en directo: al seguir, vuelve al directo, también desde los controles Bluetooth.
        player.seekToDefaultPosition();
        player.prepare();
        player.play();
    }

    private static void publishMetadata() {
        Station station = current();
        if (metadata != null && station != null) metadata.setChannel(station.name,
                nowTitle.isEmpty() ? Str.get(station.local ? R.string.hql_music_mine : R.string.hql_radio) : nowTitle, station.logo);
    }

    static void step(Context ctx, int delta) {
        if (list.isEmpty()) return;
        index = (index + delta + list.size()) % list.size();
        start(ctx.getApplicationContext());
    }

    static void stop() {
        if (player != null) {
            metadata.close();
            metadata = null;
            mediaFocus.close();
            mediaFocus = null;
            player.release();
            player = null;
        }
        index = -1;
        nowTitle = "";
        error = "";
        changed();
    }

    // ---------------------------------------------------------------- seguir en la próxima sesión

    private static final String RESUME_PREFS = "hql_radio_resume";
    /** Emisoras o canciones que se guardan como mucho, alrededor de la que sonaba: ⏮ ⏭ siguen valiendo al volver. */
    static final int RESUME_MAX = 200;

    /** Lo guardado para la próxima sesión: la lista, la que sonaba y, en una canción, por dónde iba. */
    static final class Resume {
        final List<Station> list;
        final int index;
        final long posMs;

        Resume(List<Station> list, int index, long posMs) {
            this.list = list;
            this.index = index;
            this.posMs = posMs;
        }
    }

    /**
     * Al cerrarse la interfaz propia, antes de {@link #stop()}: si sonaba algo, se guarda para seguir en la próxima
     * sesión («Seguir con la radio al conectar»); si estaba en pausa o parado, se olvida (como la radio de un coche).
     */
    static void saveForResume(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE);
        Station s = current();
        if (s == null || !isPlaying()) {
            if (p.contains("state")) L.i("radio: no sonaba al cerrar; no seguirá en la próxima conexión");
            p.edit().remove("state").apply();
            return;
        }
        p.edit().putString("state", resumeJson(list, index, s.local ? player.getCurrentPosition() : 0)).apply();
        L.i("radio: sonaba " + (s.local ? "una canción" : s.name) + " al cerrar; seguirá en la próxima conexión");
    }

    /** Al abrir la interfaz propia: sigue con lo que sonaba al cerrarse la anterior, si sonaba y no suena ya otra cosa. */
    static void resumeSaved(Context ctx) {
        if (current() != null) return;
        Resume r = resumeFrom(ctx.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).getString("state", null));
        if (r == null) return;
        list.clear();
        list.addAll(r.list);
        index = r.index;
        Station s = current();
        L.i("radio: sigo con lo que sonaba al cerrar: " + (s.local ? "canción " + (index + 1) + " de " + list.size()
                + " desde " + r.posMs / 1000 + " s" : s.name));
        start(ctx.getApplicationContext());
        if (r.posMs > 0 && player != null) player.seekTo(r.posMs);
    }

    /** Puro: la lista (como mucho RESUME_MAX alrededor de la que suena), la que suena y la posición, en JSON. */
    static String resumeJson(List<Station> l, int i, long posMs) {
        int from = Math.max(0, Math.min(i - RESUME_MAX / 2, l.size() - RESUME_MAX));
        int to = Math.min(l.size(), from + RESUME_MAX);
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (int k = from; k < to; k++) {
                Station s = l.get(k);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", s.name);
                o.put("url", s.url);
                if (s.logo != null) o.put("logo", s.logo);
                if (s.group != null) o.put("group", s.group);
                if (s.local) {
                    o.put("local", true);
                    o.put("album", s.album);
                    o.put("dur", s.durMs);
                }
                a.put(o);
            }
            return new org.json.JSONObject().put("list", a).put("index", i - from).put("pos", Math.max(0, posMs)).toString();
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Puro: lo guardado por {@link #resumeJson}, o null si no hay nada (o está roto). */
    static Resume resumeFrom(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONArray a = o.getJSONArray("list");
            List<Station> out = new ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                org.json.JSONObject e = a.getJSONObject(k);
                Station s = new Station();
                s.name = e.optString("name");
                s.url = e.optString("url");
                s.logo = e.optString("logo", null);
                s.group = e.optString("group", null);
                s.local = e.optBoolean("local");
                s.album = e.optString("album");
                s.durMs = e.optLong("dur");
                if (!s.url.isEmpty()) out.add(s);
            }
            int i = o.optInt("index", -1);
            if (out.size() != a.length() || i < 0 || i >= out.size()) return null;
            return new Resume(out, i, out.get(i).local ? Math.max(0, o.optLong("pos")) : 0);
        } catch (org.json.JSONException e) {
            return null;
        }
    }
}
