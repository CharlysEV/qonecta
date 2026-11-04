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
 * música de AA y se pausa si AA (o una llamada) lo toma. Una canción, al acabar, da paso a la siguiente. Lo que suena sale
 * en Android Auto, en el coche por Bluetooth y en la notificación del móvil (RadioSession), y desde ahí se controla.
 * Si se va la salida de audio (el Bluetooth del coche al bajarse, unos auriculares), se pausa: no sigue por el altavoz.
 * Solo hilo principal.
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
        /**
         * De una lista con usuario (M3uSource.Logins), solo en memoria: en Xtream va dentro de url; se guarda sin él
         * (RadioLibrary.item). Sin usuario, NONE y "".
         */
        M3uSource.Login login = M3uSource.Login.NONE;
        String loginId = "";
        /** Con HTTP Basic, el servidor de su lista: a él va la cabecera con login; null si no. */
        String authHost;
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
    /** Lo último que se paró a mano en este viaje (stopByUser): un «▶» de los controles lo vuelve a poner. */
    private static List<Station> lastList;
    private static int lastIndex = -1;
    /** Callada porque otra cosa se ha quedado el sonido (otra app o la TV), no por una pausa: deja Android Auto enseguida. */
    private static boolean displaced;
    /**
     * En pausa porque se fue la salida de audio (el Bluetooth del coche, unos auriculares), no por una pausa del usuario:
     * al cerrarse la interfaz propia cuenta como que sonaba («Seguir con la radio al conectar»).
     */
    private static boolean noisyPaused;
    /** HTTP Basic de lo que suena: su servidor y la cabecera (los lee el hilo de carga del reproductor); null si no. */
    private static volatile String authHost;
    private static volatile String authHeader;

    private RadioPlayer() {
    }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void changed() {
        RadioSession.notice(current(), isPlaying());
        // Si la radio tiene la música de Qonecta+ (la que ve Android Auto y manejan sus mandos): RadioSession.
        RadioSession.radio(player != null && RadioSession.sounds(true, player.getPlayWhenReady(), player.getPlaybackState()),
                player != null, displaced, lastStopped() != null);
        RadioSession.audible(player != null && player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_READY);
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
            // Con FFmpeg de reserva: FLAC, OGG u Opus que el móvil no sepa decodificar también suenan. Con la cabecera de
            // la lista de lo que suene (HTTP Basic), solo a su servidor.
            player = VideoPlayer.newPlayer(app, host -> {
                String h = authHost;
                return h != null && h.equalsIgnoreCase(host) ? authHeader : null;
            });
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
            mediaFocus = MediaFocus.bind(app, player);
            // Si se va el Bluetooth del coche (bajarse del coche) o unos auriculares, en pausa, no por el altavoz del móvil:
            // ya lo hace newPlayer (como en los vídeos y la TV).
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
                    if (metadata != null) metadata.setError(error);
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
                    // Otra app se ha quedado el sonido (ExoPlayer lo dice), o la TV (MediaFocus se lo ha pasado).
                    displaced = !playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                            || MediaFocus.takenFrom(mediaFocus));
                    noisyPaused = VideoPlayer.outputGone(playWhenReady, reason);
                    if (noisyPaused) {
                        L.i("radio: se ha ido la salida de audio (Bluetooth del coche o auriculares): en pausa, no sigue"
                                + " por el altavoz");
                    }
                    changed();
                }
            });
            // La sesión de la radio (la de Android Auto y el coche): sus órdenes van a playFromControls y compañía.
            metadata = new PlaybackMetadata(app, player, RadioSession.playerUp(app));
        }
        // Con la pantalla del móvil apagada sigue sonando: CPU (y, la radio, la Wi-Fi) despiertos mientras suena.
        player.setWakeMode(s.local ? C.WAKE_MODE_LOCAL : C.WAKE_MODE_NETWORK);
        nowTitle = s.local ? s.group : "";
        error = "";
        noisyPaused = false;
        // Ya suena algo: lo parado antes no vuelve con «▶».
        lastList = null;
        metadata.setError("");
        publishMetadata();
        RadioLibrary.addRecent(app, s);
        authHeader = s.authHost != null && !s.login.isEmpty() ? M3uSource.basicAuth(s.login) : null;
        authHost = authHeader != null ? s.authHost : null;
        player.setMediaItem(new MediaItem.Builder().setUri(s.url)
                .setMediaMetadata(new MediaMetadata.Builder().setStation(s.name).build()).build());
        player.prepare();
        player.play();
        L.i(s.local ? "música: canción " + (index + 1) + " de " + list.size()
                : "radio: " + s.name + " · " + M3uSource.mask(s.url, s.login));
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

    /**
     * A los controles (Android Auto, Bluetooth, notificación): una emisora, la canción que emite y debajo la emisora (sin
     * canción, la emisora y «Radio»), en directo; una canción del móvil, su título, el artista, el álbum y la duración.
     */
    private static void publishMetadata() {
        Station s = current();
        if (metadata == null || s == null) return;
        String[] t = RadioSession.texts(s.local, s.name, nowTitle, Str.get(R.string.hql_radio), Str.get(R.string.hql_music_mine));
        if (s.local) metadata.setNowPlaying(t[0], t[1], s.album, s.logo, "", s.durMs, false);
        else metadata.setNowPlaying(t[0], t[1], s.name, s.logo, s.name, 0, true);
    }

    static void step(Context ctx, int delta) {
        if (list.isEmpty()) return;
        index = (index + delta + list.size()) % list.size();
        start(ctx.getApplicationContext());
    }

    /**
     * Para del todo y olvida (fin de la interfaz propia, de Android Auto o de la sesión con el coche): después, un «▶» ya no
     * la vuelve a poner.
     */
    static void stop() {
        lastList = null;
        halt();
    }

    /** ⏹ del usuario (pantalla Radio, panel, Android Auto, coche): para, y un «▶» la vuelve a poner en este viaje. */
    static void stopByUser() {
        if (current() != null) {
            lastList = new ArrayList<>(list);
            lastIndex = index;
        }
        halt();
    }

    private static void halt() {
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
        displaced = false;
        noisyPaused = false;
        // Parada (con lo parado a mano, si lo hay) o liberada si Android Auto no la tiene.
        RadioSession.playerDown();
        changed();
    }

    /**
     * Se acaba la sesión con el coche sin la interfaz propia (modo «Auto»; con ella la para CarUi.stop): para del todo y
     * olvida, como cuando se va Android Auto (RadioBrowserService.onDestroy). Hace falta: sin coche, Android Auto puede
     * seguir conectado esperándolo minutos (AaPark) y RadioBrowserService con él, y la radio seguía sonando por el altavoz
     * del móvil y su sesión, en pausa, se quedaba con las teclas.
     */
    static void carGone() {
        if (CarUi.running()) return;
        if (current() != null) L.i("radio: se acaba la sesión con el coche (modo Auto): paro la radio");
        stop();
    }

    /** Lo parado a mano en este viaje que un «▶» vuelve a poner, o null. */
    static Station lastStopped() {
        return lastList != null && lastIndex >= 0 && lastIndex < lastList.size() ? lastList.get(lastIndex) : null;
    }

    // ---------------------------------------------------------------- órdenes de Android Auto, el coche y la notificación

    /**
     * «▶»: con emisora, sigue (si ya suena, nada); sin ella, vuelve lo que se paró a mano en este viaje; si tampoco hay,
     * nada. Android Auto (o el coche) lo manda a veces solo al conectar: la radio no arranca sola.
     */
    static void playFromControls(Context ctx) {
        if (current() != null) {
            if (player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_IDLE
                    && player.getPlaybackState() != Player.STATE_ENDED) return;
            resume();
            changed();
            return;
        }
        if (lastStopped() != null) {
            L.i("radio: ▶ desde los controles: vuelve la emisora parada");
            play(ctx, lastList, lastIndex);
            return;
        }
        L.i("radio: ▶ desde los controles sin emisora elegida (Android Auto o el coche al conectar): no arranca sola");
    }

    static void pauseFromControls() {
        if (player == null) return;
        player.pause();
        // Pausa del usuario (aunque ya la hubiera pausado la salida de audio): al cerrar, ya no sonaba.
        noisyPaused = false;
        changed();
    }

    /** ⏮ ⏭: la anterior o la siguiente de la lista; sin nada puesto, nada (no arranca sola). */
    static void skipFromControls(Context ctx, int delta) {
        if (current() == null) return;
        step(ctx, delta);
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
     * Al cerrarse la interfaz propia, antes de {@link #stop()}: si sonaba algo (o lo pausó solo la salida de audio al irse
     * el Bluetooth del coche, que suele irse antes que el enlace), se guarda para seguir en la próxima sesión («Seguir con
     * la radio al conectar»); si estaba en pausa o parado, se olvida (como la radio de un coche).
     */
    static void saveForResume(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE);
        Station s = current();
        if (s == null || !soundedAtClose(isPlaying(), noisyPaused)) {
            if (p.contains("state")) L.i("radio: no sonaba al cerrar; no seguirá en la próxima conexión");
            p.edit().remove("state").apply();
            return;
        }
        p.edit().putString("state", resumeJson(list, index, s.local ? player.getCurrentPosition() : 0)).apply();
        L.i("radio: sonaba " + (s.local ? "una canción" : s.name) + " al cerrar; seguirá en la próxima conexión");
    }

    /** Puro: al cerrar, sonaba: sonando, o en pausa solo porque se fue la salida de audio (no la pausó el usuario). */
    static boolean soundedAtClose(boolean playing, boolean noisyPaused) {
        return playing || noisyPaused;
    }

    /** Al abrir la interfaz propia: sigue con lo que sonaba al cerrarse la anterior, si sonaba y no suena ya otra cosa. */
    static void resumeSaved(Context ctx) {
        if (current() != null) return;
        Resume r = resumeFrom(ctx.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).getString("state", null),
                RadioLibrary.logins(ctx));
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
                // Sin el usuario de su lista, como los favoritos y las recientes.
                o.put("url", RadioLibrary.storedUrl(s));
                RadioLibrary.putLogin(o, s);
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
        return resumeFrom(json, id -> M3uSource.Login.NONE);
    }

    /** Puro: igual, con el usuario de su lista (logins) en las emisoras que lo tienen. */
    static Resume resumeFrom(String json, RadioLibrary.LoginLookup logins) {
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
                RadioLibrary.readLogin(e, s, logins);
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
