package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.core.content.IntentCompat;
import androidx.media3.common.Player;

/**
 * La sesión multimedia de la radio y de «Mi música» (MediaSessionCompat): la que enseñan Android Auto (le da su token
 * RadioBrowserService), la notificación multimedia del móvil y el coche por Bluetooth (AVRCP: título, emisora y, si el
 * coche enseña portadas, el logo). Una para todo el proceso: la crea quien la necesite primero (la radio al sonar o
 * Android Auto al conectarse a RadioBrowserService) y se libera cuando ya no la usa nadie. Mientras suena, lo que publica
 * lo pone PlaybackMetadata; aquí van sus órdenes, el estado parado y cuándo está activa (solo mientras la radio tiene la
 * música, ver «la música de Qonecta+»). Solo hilo principal, salvo lo que dice otra cosa.
 *
 * La radio nunca arranca sola: Android Auto (o el coche) puede mandar «▶» o «preparar» al conectar con la última app de
 * música, y sin emisora eso no hace nada. Suena lo que el usuario elige (una emisora en Android Auto o en la pantalla
 * Radio), lo que estaba sonando o en pausa y lo que paró a mano en este mismo viaje (RadioPlayer.stopByUser). Al conectar,
 * solo «Seguir con la radio al conectar» (Config.radioResume) la pone en marcha, como antes.
 *
 * Qonecta+ es siempre fuente de música de Android Auto; lo que Android Auto ve de ella es esta sesión, y solo mientras la
 * radio tiene la música (ver «la música de Qonecta+», abajo).
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
     * Sin reproductor: parada (activa si la radio sigue teniendo la música: parada a mano). Con una emisora parada a mano
     * en este viaje, su nombre y «▶» (la vuelve a poner); si no, vacía y sin «▶»: en Android Auto se elige una emisora de
     * la lista.
     */
    private static void idle() {
        if (session == null || inUse) return;
        RadioPlayer.Station last = RadioPlayer.lastStopped();
        session.setActive(owner);
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
        if (session == null || browsed || inUse || owner) return;
        session.setActive(false);
        session.release();
        session = null;
    }

    // ---------------------------------------------------------------- la música de Qonecta+

    /*
     * Android Auto lee sus fuentes de música al empezar su sesión (RadioBrowserService habilitado a mitad no lo ve), así
     * que Qonecta+ lo es siempre. Pero, para enseñar Qonecta+, Android Auto coge una de las sesiones activas del paquete,
     * y hay más: la del enlace con Android Auto (AapService, «HeadunitRevived»: «Android Auto · Conectado», en «sonando»
     * cuando Android Auto pone su música; por ella van a Android Auto los mandos del volante y los auriculares) y la de
     * la TV. Con la radio sonando, Android Auto cogía la del enlace (parada) y seguía con Spotify.
     *
     * Por eso la radio «tiene la música» (owner) desde que suena hasta que otra cosa se queda el sonido (otra app, la TV
     * o la música de Android Auto), Android Auto empieza o acaba una sesión sin la radio sonando, o se para del todo; en
     * pausa, con la emisora fallando o parada a mano (⏹, que ▶ vuelve a poner en este viaje) la sigue teniendo, como
     * cualquier app de música, para que Android Auto la siga enseñando y sus mandos la manejen. Mientras la tiene: esta
     * sesión, activa; la del enlace, apartada (inactiva: Link.linkChanged, linkActive); y las teclas que llegan a la del
     * enlace o del coche por QDLink, a la radio (linkKey). Sin ella: esta sesión, inactiva (Android Auto no la ve y no
     * salta a Qonecta+ con Spotify); la del enlace, como siempre; y una tecla que llegue aquí de todas formas va a Android
     * Auto, como antes. Nunca se devuelve a Android Auto una orden que manda el propio Android Auto: daría vueltas.
     */

    /** Android Auto: sus órdenes no se le devuelven. */
    static final String ANDROID_AUTO = "com.google.android.projection.gearhead";
    /** Lo que hace la radio con una tecla multimedia (keyOrder). */
    static final int KEY_NONE = 0;
    static final int KEY_PLAY = 1;
    static final int KEY_PAUSE = 2;
    static final int KEY_NEXT = 3;
    static final int KEY_PREVIOUS = 4;
    static final int KEY_STOP = 5;
    /** Adónde va una tecla multimedia (keyRoute): a Android Auto como siempre, a la radio o a ningún sitio. */
    static final int ROUTE_AA = 0;
    static final int ROUTE_RADIO = 1;
    static final int ROUTE_DROP = 2;

    /** La radio tiene la música de Qonecta+ (ver arriba). Se lee desde otros hilos (las teclas del coche). */
    private static volatile boolean owner;
    /** La radio suena (lo último que dijo RadioPlayer). */
    private static boolean sounding;
    /** La sesión del enlace con Android Auto (AapService), o null. */
    private static volatile Link link;
    /** Pruebas («radioaa fuente no»): Qonecta+ no es fuente de música de Android Auto, como en 0.3.13. */
    private static boolean labNoSource;
    /** Pruebas («radioaa enlace apartado»): la sesión del enlace, apartada siempre. */
    private static volatile boolean labLinkApart;
    private static Handler main;

    /** La sesión multimedia del enlace con Android Auto (AapService). */
    public interface Link {
        /** Ha cambiado si la sesión del enlace debe apartarse: lo vuelve a mirar (linkActive). Hilo principal. */
        void linkChanged();

        /** Una tecla multimedia a Android Auto (pulsar y soltar); false si no está conectado. */
        boolean keyToAndroidAuto(int keyCode);
    }

    public static void link(Link l) {
        link = l;
    }

    public static void unlink(Link l) {
        if (link == l) link = null;
    }

    /**
     * Al arrancar la app (App.onCreate): Qonecta+ fuente de Android Auto, como dice el manifiesto (lo que cambie una prueba
     * sobrevive al proceso), y la radio sin la música.
     */
    public static void sourceAtStart(Context c) {
        app = c.getApplicationContext();
        main = new Handler(Looper.getMainLooper());
        owner = false;
        sounding = false;
        announcePending = false;
        labNoSource = false;
        labLinkApart = false;
        setSource(true);
    }

    /**
     * Lo que hace la radio (RadioPlayer.changed): si suena, si tiene reproductor (alive), si está callada porque otra cosa
     * se ha quedado el sonido (displaced) y si hay una emisora parada a mano que ▶ vuelve a poner (resumable).
     */
    static void radio(boolean nowSounding, boolean alive, boolean displaced, boolean resumable) {
        sounding = nowSounding;
        setOwner(owns(owner, nowSounding, alive, displaced, resumable));
    }

    /** Otra cosa de Qonecta+ suena con su propia sesión (la TV): la radio, si no suena, suelta la música. */
    static void otherSounds() {
        if (!sounding) setOwner(false);
    }

    /**
     * Android Auto toma el sonido o lo suelta (AapService, el foco de su audio: su música, p. ej. Spotify). Si lo toma y
     * la radio no suena, la suelta: si no, la radio en pausa o parada le quitaría a Spotify la pantalla y los mandos.
     * Desde cualquier hilo.
     */
    public static void aaSounds(boolean playing) {
        if (playing) handler().post(RadioSession::otherSounds);
    }

    /**
     * Android Auto se conecta o se va (AapService): una radio que no suena no se queda la música de la sesión nueva (la
     * del enlace vuelve como siempre). Desde cualquier hilo.
     */
    public static void aaSessionChanged() {
        handler().post(RadioSession::otherSounds);
    }

    /** La radio tiene la música (lo ve la sesión del enlace al decidir si se aparta). Desde cualquier hilo. */
    public static boolean owner() {
        return owner;
    }

    /** La sesión del enlace con Android Auto, activa: si ella lo quiere (wanted) y la radio no tiene la música. */
    public static boolean linkActive(boolean wanted) {
        return linkActive(wanted, owner, labLinkApart);
    }

    /**
     * Una tecla multimedia que iba a ir a Android Auto: de la sesión del enlace (volante por Bluetooth, auriculares, o una
     * orden a esa sesión; callerPackage: quién la manda, o null) o del coche por QDLink (null). true: es para la radio (o
     * no va a ningún sitio) y no hay que mandarla a Android Auto. Desde cualquier hilo.
     */
    public static boolean linkKey(int keyCode, String callerPackage) {
        boolean fromAa = ANDROID_AUTO.equals(callerPackage);
        int route = keyRoute(owner, fromAa, keyOrder(keyCode, false));
        if (route == ROUTE_AA) return false;
        if (route == ROUTE_RADIO) {
            L.i("radio: tecla " + KeyEvent.keyCodeToString(keyCode) + " para la radio"
                    + (fromAa ? " (la manda Android Auto)" : " (tiene la música)"));
            Runnable r = () -> perform(keyCode);
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else handler().post(r);
        }
        return true;
    }

    /** Una tecla para la radio: lo mismo que su orden en Android Auto. */
    private static void perform(int keyCode) {
        Context c = app;
        if (c == null) return;
        switch (keyOrder(keyCode, RadioPlayer.isPlaying())) {
            case KEY_PLAY:
                RadioPlayer.playFromControls(c);
                break;
            case KEY_PAUSE:
                RadioPlayer.pauseFromControls();
                break;
            case KEY_NEXT:
                RadioPlayer.skipFromControls(c, 1);
                break;
            case KEY_PREVIOUS:
                RadioPlayer.skipFromControls(c, -1);
                break;
            case KEY_STOP:
                RadioPlayer.stopByUser();
                break;
            default:
                break;
        }
    }

    /** Tras volver a anunciar la sesión (audible), cuánto tarda en reactivarse. */
    static final long REANNOUNCE_MS = 300;
    /** La radio acaba de tomar la música y aún no ha sonado de verdad: se vuelve a anunciar al sonar, una vez. */
    private static boolean announcePending;

    /**
     * La radio suena de verdad, o no (RadioPlayer.changed: reproducir y lista, ya hay audio). Android Auto elige qué app
     * enseñar cuando cambia el sonido, y en ese momento la emisora aún carga y la app de antes (Spotify) sigue sonando: se
     * quedaba con ella (probado en el móvil el 2-11). Ya con audio y la otra en pausa, la sesión se vuelve a anunciar
     * (inactiva y activa) para que Android Auto mire otra vez. Solo la primera vez tras tomar la música: al reanudar o
     * cuando la emisora se recarga (a menudo con datos móviles), Android Auto ya enseña la radio.
     */
    static void audible(boolean now) {
        boolean pending = announcePending;
        if (now) announcePending = false;
        if (!reannounce(pending, now, owner, link != null) || session == null) return;
        L.i("radio: ya suena; se vuelve a anunciar a Android Auto");
        session.setActive(false);
        handler().postDelayed(() -> {
            if (owner && session != null) session.setActive(true);
        }, REANNOUNCE_MS);
    }

    /**
     * Puro: volver a anunciar la sesión: la radio, que acaba de tomar la música (pending), suena de verdad, la sigue
     * teniendo y Android Auto está.
     */
    static boolean reannounce(boolean pending, boolean now, boolean owner, boolean androidAuto) {
        return pending && now && owner && androidAuto;
    }

    private static void setOwner(boolean on) {
        if (owner == on) return;
        owner = on;
        announcePending = on;
        L.i(on ? "radio: tiene la música; Android Auto ve su sesión y la del enlace se aparta"
                : "radio: suelta la música; vuelve la sesión del enlace con Android Auto");
        if (session != null) session.setActive(on);
        Link l = link;
        if (l != null) l.linkChanged();
        if (!on) releaseIfUnused();
    }

    private static Handler handler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /** Qonecta+ fuente de música de Android Auto (RadioBrowserService): la del manifiesto (sí) o, en pruebas, no. */
    private static void setSource(boolean on) {
        Context c = app;
        if (c == null) return;
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c, RadioBrowserService.class);
            // Sin matar la app: puede estar sonando la radio.
            int want = on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            if (pm.getComponentEnabledSetting(cn) != want) {
                pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP);
                L.i(on ? "radio: Qonecta+ es fuente de música de Android Auto (lo ve desde su próxima sesión)"
                        : "radio: pruebas: Qonecta+ deja de ser fuente de música de Android Auto (desde su próxima sesión)");
            }
        } catch (RuntimeException e) {
            L.w("radio: no se pudo " + (on ? "poner" : "quitar") + " Qonecta+ como fuente de Android Auto ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** Pruebas («radioaa fuente si|no»): Qonecta+ fuente de música de Android Auto (la de serie) o no, como en 0.3.13. */
    static void labSource(boolean on) {
        labNoSource = !on;
        setSource(on);
    }

    /** Pruebas («radioaa enlace normal|apartado»): la sesión del enlace, apartada solo mientras la radio tiene la música o siempre. */
    static void labLinkApart(boolean apart) {
        labLinkApart = apart;
        Link l = link;
        if (l != null) l.linkChanged();
    }

    /** Pruebas: cómo está todo, para la orden «radioaa». */
    static String labStatus() {
        return "fuente " + (labNoSource ? "no" : "sí") + " · enlace " + (labLinkApart ? "apartado" : "normal")
                + " · la radio tiene la música: " + (owner ? "sí" : "no") + (link == null ? " · sin enlace" : "");
    }

    /**
     * Puro: la radio suena para Android Auto: con reproductor que quiere sonar (cargando, sonando o callado un momento
     * por una llamada o un aviso del navegador), sin haber terminado ni fallado.
     */
    static boolean sounds(boolean alive, boolean playWhenReady, int playbackState) {
        return alive && playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED;
    }

    /**
     * Puro: la radio tiene la música (was: la tenía). Suena: sí. Callada porque otra cosa se ha quedado el sonido: no. En
     * pausa o fallando (con reproductor), o parada a mano (resumable): la que tuviera. Parada del todo: no.
     */
    static boolean owns(boolean was, boolean sounding, boolean alive, boolean displaced, boolean resumable) {
        if (sounding) return true;
        if (displaced) return false;
        return was && (alive || resumable);
    }

    /** Puro: la sesión del enlace, activa: la quiere activa, la radio no tiene la música y no está apartada en pruebas. */
    static boolean linkActive(boolean wanted, boolean radioOwns, boolean labApart) {
        return wanted && !radioOwns && !labApart;
    }

    /**
     * Puro: lo que hace la radio con una tecla multimedia: ▶, ⏸, ⏯ (o el botón de los auriculares: ⏸ si suena, si no ▶),
     * ⏭, ⏮ y ⏹. Las demás, nada (KEY_NONE).
     */
    static int keyOrder(int keyCode, boolean playing) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return KEY_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return KEY_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return playing ? KEY_PAUSE : KEY_PLAY;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return KEY_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return KEY_PREVIOUS;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return KEY_STOP;
            default:
                return KEY_NONE;
        }
    }

    /**
     * Puro: adónde va una tecla que iba a Android Auto. Con la música en la radio, sus teclas a la radio (las demás, como
     * la del asistente, a Android Auto). Mandada por el propio Android Auto: a la radio o a ningún sitio, nunca de vuelta
     * (daría vueltas). Si no, a Android Auto, como siempre.
     */
    static int keyRoute(boolean radioOwns, boolean fromAndroidAuto, int order) {
        if (fromAndroidAuto) return order == KEY_NONE ? ROUTE_DROP : ROUTE_RADIO;
        if (radioOwns && order != KEY_NONE) return ROUTE_RADIO;
        return ROUTE_AA;
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
        long a = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH;
        if (resumable) a |= PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_PAUSE;
        return a;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    /** Las órdenes de Android Auto, del coche (Bluetooth) y de la notificación. */
    private static final class Orders extends MediaSessionCompat.Callback {
        @Override
        public boolean onMediaButtonEvent(Intent event) {
            // Sin la música (otra cosa ha sonado después), una tecla del volante o de los auriculares que Android mande
            // aquí va a Android Auto, como antes de que hubiera radio; nunca una que mande el propio Android Auto.
            KeyEvent k = event == null ? null : IntentCompat.getParcelableExtra(event, Intent.EXTRA_KEY_EVENT, KeyEvent.class);
            Link l = link;
            if (k == null || l == null || k.getAction() != KeyEvent.ACTION_DOWN || k.getRepeatCount() != 0) {
                return super.onMediaButtonEvent(event);
            }
            int order = keyOrder(k.getKeyCode(), false);
            if (order != KEY_NONE && keyRoute(owner, ANDROID_AUTO.equals(caller()), order) == ROUTE_AA
                    && l.keyToAndroidAuto(k.getKeyCode())) {
                L.i("radio: tecla " + KeyEvent.keyCodeToString(k.getKeyCode()) + " sin la música en la radio: a Android Auto");
                return true;
            }
            return super.onMediaButtonEvent(event);
        }

        /** Quién manda la orden (dentro de una orden), o null. */
        private static String caller() {
            try {
                MediaSessionCompat s = session;
                return s == null ? null : s.getCurrentControllerInfo().getPackageName();
            } catch (RuntimeException e) {
                return null;
            }
        }

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
        public void onPlayFromSearch(String query, Bundle extras) {
            RadioLibrary.playFromSearch(app, query);
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
