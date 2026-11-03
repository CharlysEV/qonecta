package com.headqlink.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

/**
 * Título y logo de TV/radio en los controles del sistema, del coche por Bluetooth (AVRCP) y, la radio, de Android Auto
 * (con la sesión de RadioSession). El logo va como imagen, de ART_MAX_PX de lado como mucho y sin agrandar los pequeños:
 * ni Android Auto ni el Bluetooth abren direcciones http. El de una emisora en directo, centrado en un lienzo (framed):
 * Android Auto lo estiraba a toda la tarjeta.
 */
final class PlaybackMetadata implements AutoCloseable {
    /** Clave propia de la radio de Qonecta+: la emisora (el título es lo que emite, «Artista - Canción»). La lee el karaoke. */
    static final String KEY_STATION = "com.headqlink.link.STATION";
    /** Lado máximo del logo o la portada en los controles. */
    static final int ART_MAX_PX = 512;

    private final Player player;
    private final MediaSessionCompat session;
    /** false: la sesión es de RadioSession (ella atiende las órdenes) y sigue viva al cerrar. */
    private final boolean ownsSession;
    private final boolean canPrevious;
    private final boolean canNext;
    /** Órdenes de más de la sesión compartida (elegir emisora en Android Auto). */
    private final long extraActions;
    private final RequestManager images;
    private final Player.Listener listener;
    private CustomTarget<Bitmap> image;
    private Bitmap artwork;
    private String title = "";
    private String detail = "";
    private String album = "";
    private String station = "";
    private String logo = "";
    private long durationMs;
    /** En directo (una emisora): sin posición, que no sería la de la canción. */
    private boolean live;
    /** Por qué no suena (Android Auto lo enseña), o "". */
    private String error = "";
    private long generation;
    private boolean closed;

    /** La TV (y las pruebas): una sesión propia con sus órdenes, que se libera al cerrar. */
    PlaybackMetadata(Context context, Player player, String kind, Runnable play, Runnable stop,
                     Runnable previous, Runnable next) {
        this.player = player;
        images = Glide.with(context.getApplicationContext());
        session = new MediaSessionCompat(context.getApplicationContext(), "HeadQLink-" + kind);
        ownsSession = true;
        canPrevious = previous != null;
        canNext = next != null;
        extraActions = 0;
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { if (!closed) play.run(); }
            @Override public void onPause() { if (!closed) player.pause(); }
            @Override public void onStop() { if (!closed) stop.run(); }
            @Override public void onSkipToPrevious() { if (!closed && previous != null) previous.run(); }
            @Override public void onSkipToNext() { if (!closed && next != null) next.run(); }
        }, new Handler(Looper.getMainLooper()));
        listener = listen(player);
        updateState();
    }

    /**
     * La radio: publica en la sesión de RadioSession, la que ven Android Auto y el coche y la que atiende sus órdenes
     * (⏯, ⏮ ⏭ y elegir emisora). Al cerrar no se libera: RadioSession la deja parada.
     */
    PlaybackMetadata(Context context, Player player, MediaSessionCompat shared) {
        this.player = player;
        images = Glide.with(context.getApplicationContext());
        session = shared;
        ownsSession = false;
        canPrevious = true;
        canNext = true;
        extraActions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
        listener = listen(player);
        updateState();
    }

    private Player.Listener listen(Player p) {
        Player.Listener l = new Player.Listener() {
            @Override public void onEvents(Player who, Player.Events events) { updateState(); }
        };
        p.addListener(l);
        return l;
    }

    /** La TV: el canal (título y álbum), lo de debajo («TV») y su logo. */
    void setChannel(String name, String subtitle, String artUrl) {
        setNowPlaying(name, subtitle, name, artUrl, "", 0, false);
    }

    /**
     * Lo que suena: título y subtítulo (lo que enseñan Android Auto y el coche), álbum, logo o portada, la emisora (solo
     * la radio en directo: el karaoke sabe así que el título es lo que emite), la duración (0: sin duración) y si va en
     * directo (sin posición).
     */
    void setNowPlaying(String title, String subtitle, String album, String artUrl, String station, long durationMs,
                       boolean live) {
        if (closed) return;
        this.title = nz(title);
        detail = nz(subtitle);
        this.album = nz(album);
        this.station = nz(station);
        this.durationMs = Math.max(0, durationMs);
        boolean liveChanged = live != this.live;
        this.live = live;
        String url = nz(artUrl);
        boolean newLogo = !url.equals(logo);
        logo = url;
        if (newLogo) {
            ++generation;
            // Retirar el arte antes de liberar el bitmap de Glide.
            artwork = null;
            publish();
            if (image != null) images.clear(image);
            image = null;
        } else publish();
        if (liveChanged) updateState();
        if (!newLogo || logo.isEmpty()) return;
        long requested = generation;
        boolean frame = live;
        image = new CustomTarget<Bitmap>(ART_MAX_PX, ART_MAX_PX) {
            @Override public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                if (closed || requested != generation) return;
                // Una copia propia: MediaSession la conserva aunque Glide libere la suya. El logo de una emisora, en
                // su lienzo (framed); la portada de una canción, tal cual.
                artwork = frame ? framed(bitmap) : fitted(bitmap, ART_MAX_PX);
                publish();
            }
            @Override public void onLoadCleared(@Nullable Drawable placeholder) { }
        };
        // Sin agrandar al decodificar: un logo de 32 px agrandado a 512 no gana nada (el de una emisora lo agranda
        // framed, como mucho FRAME_GROW veces).
        images.asBitmap().load(logo).downsample(DownsampleStrategy.CENTER_INSIDE).into(image);
    }

    /** Por qué no suena (Android Auto lo enseña con el estado de error); "" cuando vuelve a sonar. */
    void setError(String message) {
        if (closed) return;
        error = nz(message);
        updateState();
    }

    private void publish() {
        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, detail)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, detail);
        if (!station.isEmpty()) meta.putString(KEY_STATION, station);
        if (durationMs > 0) meta.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        if (artwork != null) {
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        session.setMetadata(meta.build());
    }

    private void updateState() {
        if (closed) return;
        int playback = player.getPlaybackState();
        boolean playing = player.getPlayWhenReady() && player.getPlaybackSuppressionReason() == Player.PLAYBACK_SUPPRESSION_REASON_NONE;
        int state = stateOf(playback, player.getPlayWhenReady(), player.getPlaybackSuppressionReason(), !error.isEmpty());
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setActions(actions(canPrevious, canNext) | extraActions)
                .setState(state, live ? PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN : Math.max(0, player.getCurrentPosition()),
                        playing ? 1f : 0f);
        if (state == PlaybackStateCompat.STATE_ERROR) b.setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, error);
        session.setPlaybackState(b.build());
        // La de la radio la activa RadioSession (mientras la radio tiene la música, también en pausa).
        if (!ownsSession) return;
        // La TV: activa solo mientras suena (en pausa no compite con Spotify ni con la sesión de Android Auto), y la
        // radio, si no suena, suelta la música: Android Auto no la enseña mientras suena la TV.
        boolean active = playing && playback != Player.STATE_IDLE && playback != Player.STATE_ENDED;
        session.setActive(active);
        if (active) RadioSession.otherSounds();
    }

    /**
     * Puro: el estado de los controles según el reproductor. Parado (o con error, si falló) sin nada cargado; en pausa
     * si no va a sonar (o lo calla otra app que tiene el sonido); cargando o sonando.
     */
    static int stateOf(int playback, boolean playWhenReady, int suppression, boolean failed) {
        if (playback == Player.STATE_IDLE) return failed ? PlaybackStateCompat.STATE_ERROR : PlaybackStateCompat.STATE_STOPPED;
        if (playback == Player.STATE_ENDED) return PlaybackStateCompat.STATE_STOPPED;
        if (!playWhenReady || suppression != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return PlaybackStateCompat.STATE_PAUSED;
        return playback == Player.STATE_BUFFERING ? PlaybackStateCompat.STATE_BUFFERING : PlaybackStateCompat.STATE_PLAYING;
    }

    /** Puro: lo que se puede pedir desde los controles mientras hay algo cargado. */
    static long actions(boolean previous, boolean next) {
        long a = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP;
        if (previous) a |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        if (next) a |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        return a;
    }

    /** Una copia propia (en memoria normal) del bitmap, de max px de lado como mucho; null si no se puede copiar. */
    static Bitmap fitted(Bitmap src, int max) {
        Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, false);
        if (copy == null) return null;
        int[] size = fit(copy.getWidth(), copy.getHeight(), max);
        if (size[0] == copy.getWidth() && size[1] == copy.getHeight()) return copy;
        Bitmap scaled = Bitmap.createScaledBitmap(copy, size[0], size[1], true);
        if (scaled != copy) copy.recycle();
        return scaled;
    }

    /** Puro: el tamaño que cabe en max × max sin deformar ni agrandar (1 px como poco). */
    static int[] fit(int w, int h, int max) {
        if (w <= 0 || h <= 0) return new int[]{Math.max(1, w), Math.max(1, h)};
        if (w <= max && h <= max) return new int[]{w, h};
        double k = Math.min((double) max / w, (double) max / h);
        return new int[]{Math.max(1, (int) Math.round(w * k)), Math.max(1, (int) Math.round(h * k))};
    }

    // ---------------------------------------------------------------- el logo de una emisora
    // Android Auto estira la carátula a toda su tarjeta: un logo de 64-150 px salía enorme y borroso (foro #341). El de
    // una emisora va centrado en un lienzo cuadrado: Android Auto estira el lienzo y el logo queda pequeño y nítido. Es la
    // misma imagen para el coche por Bluetooth (el cuadro la reduce a su miniatura) y la notificación del móvil.

    /** Lado del lienzo del logo de una emisora. */
    static final int FRAME_PX = ART_MAX_PX;
    /** El logo ocupa como mucho esta parte del lado del lienzo... */
    static final double FRAME_SHARE = 0.6;
    /** ...y se agranda como mucho tanto (más, solo se vería borroso). */
    static final double FRAME_GROW = 2.0;
    /** Fondo de un logo transparente claro o de colores: el de las tarjetas de la app (hql_surface). */
    static final int FRAME_DARK = 0xFF111821;
    /** Fondo de un logo transparente oscuro (letras negras o azul marino), que sobre el oscuro no se vería. */
    static final int FRAME_LIGHT = 0xFFE6EAEF;

    /** El logo de una emisora centrado en su lienzo de FRAME_PX (opaco); null si no se puede copiar. */
    static Bitmap framed(Bitmap src) {
        Bitmap logo = src.copy(Bitmap.Config.ARGB_8888, false);
        if (logo == null) return null;
        try {
            int w = logo.getWidth();
            int h = logo.getHeight();
            int[] px = new int[w * h];
            logo.getPixels(px, 0, w, 0, 0, w, h);
            int[] r = frameRect(w, h, FRAME_PX);
            Bitmap out = Bitmap.createBitmap(FRAME_PX, FRAME_PX, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(frameBackground(px, w, h));
            // Lo transparente del logo deja ver el fondo.
            c.drawBitmap(logo, null, new Rect(r[0], r[1], r[0] + r[2], r[1] + r[3]),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            return out;
        } finally {
            logo.recycle();
        }
    }

    /**
     * Puro: dónde va un logo de w × h en el lienzo de side px: {x, y, ancho, alto}, centrado y sin deformar; agrandado
     * como mucho FRAME_GROW veces y como mucho FRAME_SHARE del lado (los grandes se reducen hasta ahí).
     */
    static int[] frameRect(int w, int h, int side) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        double k = Math.min(FRAME_GROW, side * FRAME_SHARE / Math.max(w, h));
        int dw = Math.max(1, (int) Math.round(w * k));
        int dh = Math.max(1, (int) Math.round(h * k));
        return new int[]{(side - dw) / 2, (side - dh) / 2, dw, dh};
    }

    /**
     * Puro: el fondo del lienzo para un logo (sus píxeles ARGB, w × h). Con el borde opaco y de un solo color (un logo
     * sobre blanco, un cuadrado de color), ese color: el lienzo parece el logo entero. Con el borde transparente, el
     * oscuro de la app, o el claro si lo que se ve del logo es oscuro. Con un borde opaco variado (una foto), el oscuro.
     */
    static int frameBackground(int[] px, int w, int h) {
        if (w <= 0 || h <= 0 || px.length < w * h) return FRAME_DARK;
        int n = 0;
        int opaque = 0;
        long r = 0, g = 0, b = 0;
        for (int i = 0; i < w * h; i++) {
            int x = i % w;
            int y = i / w;
            if (x != 0 && x != w - 1 && y != 0 && y != h - 1) continue;
            n++;
            int p = px[i];
            if ((p >>> 24) < 0xF0) continue;
            opaque++;
            r += (p >> 16) & 0xFF;
            g += (p >> 8) & 0xFF;
            b += p & 0xFF;
        }
        if (opaque * 10 >= n * 9) {
            int mr = (int) (r / opaque), mg = (int) (g / opaque), mb = (int) (b / opaque);
            int alike = 0;
            for (int i = 0; i < w * h; i++) {
                int x = i % w;
                int y = i / w;
                if (x != 0 && x != w - 1 && y != 0 && y != h - 1) continue;
                int p = px[i];
                if ((p >>> 24) >= 0xF0 && Math.abs(((p >> 16) & 0xFF) - mr) <= 32
                        && Math.abs(((p >> 8) & 0xFF) - mg) <= 32 && Math.abs((p & 0xFF) - mb) <= 32) alike++;
            }
            return alike * 10 >= n * 9 ? 0xFF000000 | (mr << 16) | (mg << 8) | mb : FRAME_DARK;
        }
        // Borde transparente: cómo de claro es lo que se ve del logo (luminancia, pesada por su opacidad).
        double lum = 0, weight = 0;
        for (int p : px) {
            int a = p >>> 24;
            if (a < 0x40) continue;
            lum += a * (0.2126 * ((p >> 16) & 0xFF) + 0.7152 * ((p >> 8) & 0xFF) + 0.0722 * (p & 0xFF));
            weight += a;
        }
        return weight > 0 && lum / weight < 64 ? FRAME_LIGHT : FRAME_DARK;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ++generation;
        player.removeListener(listener);
        // La de la radio sigue viva (Android Auto puede tenerla): su estado parado, y si sigue activa, los pone RadioSession.
        if (ownsSession) {
            session.setActive(false);
            session.setMetadata(null);
            session.release();
        }
        if (image != null) images.clear(image);
        image = null;
        artwork = null;
    }
}
