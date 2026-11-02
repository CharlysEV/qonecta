package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media3.common.Player;

/**
 * La sesión multimedia de la radio y de «Mi música» (MediaSessionCompat): la que enseñan Android Auto (le da su token
 * RadioBrowserService), la notificación multimedia del móvil y el coche por Bluetooth (AVRCP: título, emisora y, si el
 * coche enseña portadas, el logo). Una para todo el proceso: la crea quien la necesite primero (la radio al sonar o
 * Android Auto al conectarse a RadioBrowserService) y se libera cuando ya no la usa ninguno de los dos. Mientras suena,
 * lo que publica lo pone PlaybackMetadata (activa solo mientras suena: una radio en pausa no compite con Spotify); aquí
 * van sus órdenes y el estado parado. Solo hilo principal.
 *
 * La radio nunca arranca sola: Android Auto (o el coche) puede mandar «▶» o «preparar» al conectar con la última app de
 * música, y sin emisora eso no hace nada. Suena lo que el usuario elige (una emisora en Android Auto o en la pantalla
 * Radio), lo que estaba sonando o en pausa y lo que paró a mano en este mismo viaje (RadioPlayer.stopByUser). Al conectar,
 * solo «Seguir con la radio al conectar» (Config.radioResume) la pone en marcha, como antes.
 *
 * Qonecta+ solo es fuente de música de Android Auto mientras suena la radio (ver «fuente de Android Auto», abajo).
 */
public final class RadioSession {
    private static final String CHANNEL = "hql_radio";
    private static final int NOTICE_ID = 0x5241;

    private static MediaSessionCompat session;
    private static Context app;
    /** RadioBrowserService vivo (Android Auto conectado a la radio): la sesión no se libera aunque la radio pare. */
    private static boolean browsed;
    /** El reproductor de la radio la está usando (de RadioPlayer.start a su parada). */
    private static boolean inUse;
    /** Lo último puesto en la notificación del móvil («url|suena|título»), para no repetirla en cada cambio. */
    private static String noticeKey = "";

    private RadioSession() {
    }

    /** La sesión, creada (y parada) si no la hay. */
    static MediaSessionCompat get(Context c) {
        if (session == null) {
            app = c.getApplicationContext();
            session = new MediaSessionCompat(app, "HeadQLink-Radio");
            session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new Orders(), new Handler(Looper.getMainLooper()));
            idle();
            // La notificación de un proceso anterior (cerrado con la radio sonando) ya no tiene sesión detrás.
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && noticeKey.isEmpty()) nm.cancel(NOTICE_ID);
        }
        return session;
    }

    /** Android Auto se conecta a la radio (RadioBrowserService.onCreate): su token. */
    static MediaSessionCompat.Token browserUp(Context c) {
        browsed = true;
        return get(c).getSessionToken();
    }

    /** Android Auto se ha ido: si la radio no la usa, fuera. */
    static void browserDown() {
        browsed = false;
        releaseIfUnused();
    }

    /** RadioPlayer empieza a sonar con un reproductor nuevo: su PlaybackMetadata publica aquí. */
    static MediaSessionCompat playerUp(Context c) {
        MediaSessionCompat s = get(c);
        inUse = true;
        return s;
    }

    /** RadioPlayer ha parado: la sesión queda parada, o se libera si Android Auto no la tiene. */
    static void playerDown() {
        inUse = false;
        idle();
        releaseIfUnused();
    }

    /** El token (MediaController.getSessionToken de otra sesión) es el de esta: el karaoke la reconoce así. */
    static boolean isToken(Object token) {
        return session != null && token != null && token.equals(session.getSessionToken().getToken());
    }

    /**
     * Sin reproductor: parada. Con una emisora parada a mano en este viaje, su nombre y «▶» (la vuelve a poner); si no,
     * vacía y sin «▶»: en Android Auto se elige una emisora de la lista.
     */
    private static void idle() {
        if (session == null || inUse) return;
        RadioPlayer.Station last = RadioPlayer.lastStopped();
        session.setActive(false);
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(idleActions(last != null))
                .setState(last != null ? PlaybackStateCompat.STATE_STOPPED : PlaybackStateCompat.STATE_NONE,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build());
        if (last == null) {
            session.setMetadata(null);
            return;
        }
        String[] t = texts(last.local, last.name, last.local ? last.group : "", Str.get(R.string.hql_radio),
                Str.get(R.string.hql_music_mine));
        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t[0])
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t[1])
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, t[0])
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, t[1])
                .build());
    }

    private static void releaseIfUnused() {
        if (session == null || browsed || inUse) return;
        session.setActive(false);
        session.release();
        session = null;
    }

    // ---------------------------------------------------------------- fuente de Android Auto

    /*
     * Qonecta+ solo es fuente de música de Android Auto (RadioBrowserService habilitado; el manifiesto lo trae
     * deshabilitado) mientras suena la radio. Android Auto salta a la app que tenga una sesión sonando y un servicio de
     * música, y Qonecta+ tiene más sesiones que suenan sin ser la radio: la del enlace con Android Auto (HeadunitRevived,
     * en «sonando» desde que conecta y cada vez que Android Auto toma el sonido, o sea, con Spotify; por ella van los
     * mandos del volante del C10) y la de la TV. Con el servicio siempre puesto, Android Auto saltaría a «Qonecta+» al
     * conectar o con Spotify sonando y enseñaría esta sesión vacía (#297 al revés). Así, sin radio, todo sigue como antes
     * de que la radio saliera en Android Auto; a cambio, «Favoritos» y «Recientes» solo están mientras suena.
     */

    /** Tras parar, o si otra cosa se queda el sonido (otra app, la TV o la música de Android Auto): lo que sigue de fuente. */
    static final long OFF_SOON_MS = 2_000;
    /** Tras una pausa (⏯ en Android Auto, el volante o la pantalla Radio) o un fallo de la emisora: lo que sigue de fuente. */
    static final long OFF_PAUSED_MS = 60_000;

    /** El servicio está habilitado ahora (null: sin saber todavía). */
    private static Boolean sourceOn;
    /** Cuándo (uptimeMillis) deja de ser fuente, o 0 si no está previsto. */
    private static long offAt;
    /** La radio suena (lo último que dijo RadioPlayer). */
    private static boolean sounding;
    /** Variante de pruebas («radioaa siempre»): fuente siempre, como antes del arreglo, para ver si Android Auto salta. */
    private static boolean labAlways;
    private static Handler main;
    private static final Runnable SOURCE_OFF = () -> {
        offAt = 0;
        setSource(false);
    };

    /**
     * Al arrancar la app (App.onCreate): sin radio, Qonecta+ no es fuente. Lo habilitado sobrevive al proceso: si se
     * cerró con la radio sonando, Android Auto lo seguiría viendo.
     */
    public static void sourceAtStart(Context c) {
        app = c.getApplicationContext();
        if (main != null) main.removeCallbacks(SOURCE_OFF);
        main = new Handler(Looper.getMainLooper());
        offAt = 0;
        sourceOn = null;
        sounding = false;
        labAlways = false;
        setSource(false);
    }

    /**
     * Lo que hace la radio (RadioPlayer.changed): suena → fuente ya; deja de sonar → deja de serlo al rato
     * (offDelayMs: alive, con reproductor; displaced, callada porque otra cosa se ha quedado el sonido).
     */
    static void radio(boolean nowSounding, boolean alive, boolean displaced) {
        sounding = nowSounding;
        if (nowSounding) {
            handler().removeCallbacks(SOURCE_OFF);
            offAt = 0;
            setSource(true);
        } else {
            offIn(offDelayMs(alive, displaced));
        }
    }

    /** Otra cosa de Qonecta+ suena con su propia sesión (la TV): la radio, si no suena, deja de ser fuente enseguida. */
    static void otherSounds() {
        if (!sounding) offIn(OFF_SOON_MS);
    }

    /**
     * Android Auto toma el sonido o lo suelta (AapService, el foco de su audio: su música, p. ej. Spotify). Si lo toma y
     * la radio no suena, Qonecta+ deja de ser fuente enseguida: si no, la radio en pausa le quitaría la pantalla de
     * Android Auto a Spotify. Desde cualquier hilo.
     */
    public static void aaSounds(boolean playing) {
        if (!playing) return;
        new Handler(Looper.getMainLooper()).post(RadioSession::otherSounds);
    }

    /** Variante de pruebas: fuente siempre (true) o solo mientras suena (false). */
    static void labAlways(boolean always) {
        labAlways = always;
        if (always) {
            handler().removeCallbacks(SOURCE_OFF);
            offAt = 0;
            setSource(true);
        } else if (!sounding) {
            offIn(OFF_SOON_MS);
        }
    }

    static boolean labAlways() {
        return labAlways;
    }

    private static void offIn(long ms) {
        if (labAlways || Boolean.FALSE.equals(sourceOn)) return;
        long at = SystemClock.uptimeMillis() + ms;
        // Una ya prevista antes se queda: los avisos de la radio en pausa no la van retrasando.
        if (offAt != 0 && offAt <= at) return;
        offAt = at;
        handler().removeCallbacks(SOURCE_OFF);
        handler().postAtTime(SOURCE_OFF, at);
    }

    private static Handler handler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    private static void setSource(boolean on) {
        Context c = app;
        if (c == null || Boolean.valueOf(on).equals(sourceOn)) return;
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c, RadioBrowserService.class);
            // Apagada, la del manifiesto (deshabilitado). Sin matar la app: suena la radio.
            int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            if (pm.getComponentEnabledSetting(cn) != want) {
                pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP);
                L.i(on ? "radio: suena; Qonecta+ es fuente de música de Android Auto"
                        : "radio: no suena; Qonecta+ deja de ser fuente de música de Android Auto");
            }
            sourceOn = on;
        } catch (RuntimeException e) {
            L.w("radio: no se pudo " + (on ? "poner" : "quitar") + " Qonecta+ como fuente de Android Auto ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Puro: la radio suena para Android Auto: con reproductor que quiere sonar (cargando, sonando o callado un momento
     * por una llamada o un aviso del navegador), sin haber terminado ni fallado.
     */
    static boolean sounds(boolean alive, boolean playWhenReady, int playbackState) {
        return alive && playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED;
    }

    /**
     * Puro: cuánto sigue Qonecta+ de fuente cuando la radio deja de sonar. Parada, o callada porque otra cosa se ha
     * quedado el sonido: enseguida. En pausa o con la emisora fallando: un rato, para que ⏯ (o elegir otra emisora) la
     * vuelva a poner sin que Android Auto la haya soltado.
     */
    static long offDelayMs(boolean alive, boolean displaced) {
        return !alive || displaced ? OFF_SOON_MS : OFF_PAUSED_MS;
    }

    // ---------------------------------------------------------------- notificación del móvil

    /**
     * La notificación multimedia del móvil mientras hay emisora (sonando o en pausa); fuera al parar. Android 13+ la pinta
     * con lo que publica la sesión (título, emisora, logo y botones) y, por ser de una sesión multimedia, no necesita el
     * permiso de notificaciones. Se puede quitar deslizando: si el proceso se cierra sonando, no se queda fija.
     */
    static void notice(RadioPlayer.Station s, boolean playing) {
        Context c = app;
        if (c == null) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (s == null || session == null) {
            if (!noticeKey.isEmpty()) nm.cancel(NOTICE_ID);
            noticeKey = "";
            return;
        }
        String[] t = texts(s.local, s.name, s.local ? s.group : RadioPlayer.nowTitle, Str.get(R.string.hql_radio),
                Str.get(R.string.hql_music_mine));
        String key = s.url + "|" + playing + "|" + t[0];
        if (key.equals(noticeKey)) return;
        noticeKey = key;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_radio), NotificationManager.IMPORTANCE_LOW));
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_radio)
                    .setContentTitle(t[0])
                    .setContentText(t[1])
                    .setShowWhen(false)
                    .setSilent(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(PendingIntent.getActivity(c, NOTICE_ID, HomeActivity.openIntent(c), flags))
                    .setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.getSessionToken()));
            nm.notify(NOTICE_ID, b.build());
        } catch (RuntimeException e) {
            L.w("radio: no se pudo poner la notificación (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---------------------------------------------------------------- puro

    /**
     * Puro: título y subtítulo para los controles. Una emisora: la canción que emite (ICY) y debajo la emisora; si no dice
     * ninguna, la emisora y debajo «Radio» (radioLabel). Una canción del móvil: su título y el artista (o «Mi música»).
     */
    static String[] texts(boolean local, String name, String detail, String radioLabel, String musicLabel) {
        String n = clean(name);
        String d = clean(detail);
        if (local) return new String[]{n, d.isEmpty() ? musicLabel : d};
        if (!isSong(d, n)) return new String[]{n, radioLabel};
        return new String[]{d, n};
    }

    /** Puro: lo que emite la emisora (ICY) dice una canción: ni vacío, ni solo guiones, ni el nombre de la emisora. */
    static boolean isSong(String icy, String station) {
        String t = clean(icy);
        if (t.replaceAll("[\\s\\p{Punct}–—·•]", "").isEmpty()) return false;
        return !t.equalsIgnoreCase(clean(station));
    }

    /**
     * Puro: para el karaoke, {artista, canción} de lo que emite la emisora («Artista - Canción»; sin « - », solo la
     * canción), o null si no dice ninguna.
     */
    static String[] song(String title, String station) {
        if (!isSong(title, station)) return null;
        String t = clean(title);
        int i = t.indexOf(" - ");
        if (i > 0 && i + 3 < t.length()) return new String[]{t.substring(0, i).trim(), t.substring(i + 3).trim()};
        return new String[]{"", t};
    }

    /** Puro: las órdenes de la radio parada: elegir emisora siempre; «▶» solo si hay una parada a mano que volver a poner. */
    static long idleActions(boolean resumable) {
        long a = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
        if (resumable) a |= PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_PAUSE;
        return a;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    /** Las órdenes de Android Auto, del coche (Bluetooth) y de la notificación. */
    private static final class Orders extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            RadioPlayer.playFromControls(app);
        }

        @Override
        public void onPause() {
            RadioPlayer.pauseFromControls();
        }

        @Override
        public void onStop() {
            RadioPlayer.stopByUser();
        }

        @Override
        public void onSkipToNext() {
            RadioPlayer.skipFromControls(app, 1);
        }

        @Override
        public void onSkipToPrevious() {
            RadioPlayer.skipFromControls(app, -1);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            RadioLibrary.play(app, mediaId);
        }

        @Override
        public void onPrepare() {
            // Android Auto lo pide al conectar con la última app de música: no se carga ni se oye nada hasta elegir o ▶.
            L.i("radio: Android Auto pide preparar la radio: nada hasta que elijas una emisora o pulses ▶");
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            onPrepare();
        }
    }
}
