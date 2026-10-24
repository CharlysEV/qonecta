package com.headqlink.link;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Karaoke: la letra de lo que suena en el móvil (Spotify, YouTube Music… también con Android Auto), sincronizada con la
 * canción, de LRCLIB (Lyrics). Lo que suena y por dónde va salen de las sesiones multimedia de Android, con el mismo
 * «Acceso a notificaciones» que la lectura de Google Maps (MapsNavListener); sin él, se explica cómo darlo.
 * - Parado: la letra entera, con la línea de ahora resaltada y centrada, avanzando sola.
 * - En marcha (más de 10 km/h): solo la línea de ahora y la siguiente, grandes y sin desplazarse, para no distraer.
 * Arriba, la portada, el título y anterior / pausa / siguiente. Se enciende en Ajustes › Botones del panel lateral y
 * va también junto a AA con la pantalla partida (con el aviso del ocio). El registro no lleva títulos.
 */
final class KaraokeScreen implements CarScreen {
    static final long TICK_MS = 200;
    /** En marcha por encima de esto (solo la línea de ahora y la siguiente). */
    static final double MOVING_KMH = 10;
    /** Sin respuesta de LRCLIB: la misma canción, otra vez a los 20 s (mientras siga sonando). */
    static final long RETRY_MS = 20_000;

    /** false: sin sesiones multimedia ni sensores (las pruebas le pasan canción, letra y posición). */
    private final boolean live;
    private Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView cover;
    private TextView title;
    private TextView artist;
    private ImageView playButton;
    private FrameLayout body;
    private TextView source;
    // Letra sincronizada: parado (todas las líneas) o en marcha (ahora y la siguiente).
    private ScrollView scroll;
    private final List<TextView> lineViews = new ArrayList<>();
    private TextView nowLine;
    private TextView nextLine;
    private Lyrics.Result lyrics;
    private int shownIdx = -2;
    private boolean moving;
    private long testPosMs = -1;
    // Lo que suena.
    private MediaSessionManager msm;
    private MediaSessionManager.OnActiveSessionsChangedListener sessions;
    private MediaController ctrl;
    private String trackKey = "";
    private int fetchSeq;
    private CarSensors sensors;
    private long lastMoveCheck;
    private boolean destroyed;
    private final Runnable tick = this::tick;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata md) {
            onTrack(md);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            renderPlay();
        }

        @Override
        public void onSessionDestroyed() {
            pick();
        }
    };

    KaraokeScreen() {
        this(true);
    }

    KaraokeScreen(boolean live) {
        this.live = live;
    }

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(28, 20, 28, 12);
        col.setBackgroundColor(CarKit.BG);
        col.addView(header(c));
        body = new FrameLayout(c);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bl.topMargin = 14;
        col.addView(body, bl);
        source = CarStyle.text(c, Str.get(R.string.hql_karaoke_source), 17, CarStyle.TEXT_DIM);
        source.setPadding(6, 8, 0, 0);
        col.addView(source);
        message(Str.get(R.string.hql_karaoke_nothing));
        if (live) {
            sensors = CarSensors.start(c);
            connect(c);
            main.post(tick);
        }
        return col;
    }

    @Override
    public void destroy() {
        destroyed = true;
        main.removeCallbacks(tick);
        if (ctrl != null) ctrl.unregisterCallback(callback);
        ctrl = null;
        if (msm != null && sessions != null) msm.removeOnActiveSessionsChangedListener(sessions);
        if (sensors != null) CarSensors.stop();
        sensors = null;
    }

    private View header(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(CarStyle.round(CarStyle.ITEM_BG, 16));
        cover.setClipToOutline(true);
        row.addView(cover, new LinearLayout.LayoutParams(104, 104));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(22, 0, 12, 0);
        title = CarStyle.text(c, Str.get(R.string.hql_karaoke), 30, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        texts.addView(artist);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView prev = control(c, R.drawable.hql_ic_prev);
        playButton = control(c, R.drawable.hql_ic_play);
        ImageView next = control(c, R.drawable.hql_ic_next);
        row.addView(prev);
        row.addView(playButton);
        row.addView(next);
        prev.setOnClickListener(v -> {
            if (ctrl != null) ctrl.getTransportControls().skipToPrevious();
        });
        next.setOnClickListener(v -> {
            if (ctrl != null) ctrl.getTransportControls().skipToNext();
        });
        playButton.setOnClickListener(v -> {
            if (ctrl == null) return;
            if (playing(ctrl.getPlaybackState())) ctrl.getTransportControls().pause();
            else ctrl.getTransportControls().play();
        });
        setCover(null);
        return row;
    }

    private static ImageView control(Context c, int icon) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(CarStyle.TEXT));
        b.setBackground(CarStyle.round(CarStyle.PILL_BG, 30));
        b.setPadding(20, 20, 20, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CarStyle.touch(84), CarStyle.touch(72));
        lp.leftMargin = 10;
        b.setLayoutParams(lp);
        return b;
    }

    private void setCover(Bitmap b) {
        if (b != null) {
            cover.setPadding(0, 0, 0, 0);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageTintList(null);
            cover.setImageBitmap(b);
        } else {
            cover.setPadding(22, 22, 22, 22);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setImageResource(R.drawable.hql_ic_karaoke);
            cover.setImageTintList(ColorStateList.valueOf(CarStyle.TEXT_DIM));
        }
    }

    // ------------------------------------------------------------------ lo que suena

    private void connect(Context c) {
        if (!MapsNavListener.granted(c)) {
            message(Str.get(R.string.hql_karaoke_need_access, Str.get(R.string.hql_maps_read)));
            return;
        }
        msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return;
        ComponentName who = new ComponentName(c, MapsNavListener.class);
        sessions = list -> pick();
        try {
            msm.addOnActiveSessionsChangedListener(sessions, who, main);
            pick();
        } catch (SecurityException e) {
            sessions = null;
            message(Str.get(R.string.hql_karaoke_need_access, Str.get(R.string.hql_maps_read)));
        }
    }

    /** La sesión que suena (o, si ninguna, la primera); se sigue a esa. */
    private void pick() {
        if (destroyed || msm == null) return;
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(host.context(), MapsNavListener.class));
        } catch (SecurityException e) {
            return;
        }
        MediaController best = null;
        for (MediaController m : list) {
            if (playing(m.getPlaybackState())) {
                best = m;
                break;
            }
            if (best == null && m.getMetadata() != null) best = m;
        }
        if (best != null && ctrl != null && best.getSessionToken().equals(ctrl.getSessionToken())) return;
        if (ctrl != null) ctrl.unregisterCallback(callback);
        ctrl = best;
        if (ctrl != null) {
            ctrl.registerCallback(callback, main);
            L.i("karaoke: sigo la música de " + ctrl.getPackageName());
        }
        onTrack(ctrl == null ? null : ctrl.getMetadata());
    }

    private static boolean playing(PlaybackState s) {
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** Canción nueva (o la misma con otros datos): la cabecera y, si cambia de canción, su letra. */
    private void onTrack(MediaMetadata md) {
        if (destroyed) return;
        renderPlay();
        if (md == null) {
            trackKey = "";
            title.setText(Str.get(R.string.hql_karaoke));
            artist.setText("");
            setCover(null);
            lyrics = null;
            message(Str.get(R.string.hql_karaoke_nothing));
            return;
        }
        String t = text(md, MediaMetadata.METADATA_KEY_TITLE);
        String a = Lyrics.stripBadge(text(md, MediaMetadata.METADATA_KEY_ARTIST));
        if (a.isEmpty()) a = Lyrics.stripBadge(text(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        // Sin artista y con «Artista - Título» (radios), se separa.
        if (a.isEmpty() && t.contains(" - ")) {
            a = t.substring(0, t.indexOf(" - ")).trim();
            t = t.substring(t.indexOf(" - ") + 3).trim();
        }
        String album = text(md, MediaMetadata.METADATA_KEY_ALBUM);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String key = a + "|" + t + "|" + Math.round(dur / 1000.0);
        if (key.equals(trackKey)) {
            if (art != null) setCover(art);
            return;
        }
        trackKey = key;
        title.setText(t.isEmpty() ? Str.get(R.string.hql_karaoke) : t);
        artist.setText(a);
        setCover(art);
        if (t.isEmpty()) {
            lyrics = null;
            message(Str.get(R.string.hql_karaoke_nothing));
            return;
        }
        load(a, t, album, dur);
    }

    private static String text(MediaMetadata md, String key) {
        CharSequence s = md.getText(key);
        return s == null ? "" : s.toString().trim();
    }

    private void load(String a, String t, String album, long dur) {
        int seq = ++fetchSeq;
        lyrics = null;
        Lyrics.Result known = Lyrics.cached(a, t, dur);
        if (known != null) {
            show(known);
            return;
        }
        message(Str.get(R.string.hql_karaoke_searching));
        new Thread(() -> {
            Lyrics.Result r = null;
            try {
                r = Lyrics.fetch(a, t, album, dur);
                L.i("karaoke: " + (!r.synced.isEmpty() ? "letra sincronizada (" + r.synced.size() + " líneas)"
                        : r.instrumental ? "instrumental" : !r.plain.isEmpty() ? "letra sin sincronizar" : "sin letra"));
            } catch (Exception e) {
                L.w("karaoke: LRCLIB no responde (" + Http.safeError(e) + ")");
            }
            Lyrics.Result fr = r;
            main.post(() -> {
                if (destroyed || seq != fetchSeq) return;
                if (fr != null) {
                    show(fr);
                    return;
                }
                message(Str.get(R.string.hql_karaoke_offline));
                // La misma canción, otra vez dentro de un rato (si sigue sonando: si no, fetchSeq ya cambió).
                main.postDelayed(() -> {
                    if (!destroyed && seq == fetchSeq) load(a, t, album, dur);
                }, RETRY_MS);
            });
        }, "karaoke-lyrics").start();
    }

    // ------------------------------------------------------------------ la letra

    /** Vacía la zona de la letra. */
    private void reset() {
        lineViews.clear();
        scroll = null;
        nowLine = null;
        nextLine = null;
        shownIdx = -2;
        body.removeAllViews();
    }

    private void message(String s) {
        reset();
        body.addView(CarStyle.message(host.context(), s), CarStyle.match());
    }

    private void show(Lyrics.Result r) {
        lyrics = r;
        if (!r.synced.isEmpty()) {
            buildSynced();
        } else if (r.instrumental) {
            message(Str.get(R.string.hql_karaoke_instrumental));
        } else if (!r.plain.isEmpty()) {
            buildPlain(r.plain);
        } else {
            message(Str.get(R.string.hql_karaoke_not_found));
        }
    }

    /** Solo el texto (sin tiempos): entera y a mano. */
    private void buildPlain(String text) {
        Context c = host.context();
        reset();
        ScrollView sv = new ScrollView(c);
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(CarStyle.label(c, Str.get(R.string.hql_karaoke_unsynced)));
        TextView t = CarStyle.text(c, text, 30, CarStyle.TEXT);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, 10, 0, 20);
        l.addView(t);
        sv.addView(l);
        body.addView(sv, CarStyle.match());
    }

    private void buildSynced() {
        Context c = host.context();
        reset();
        if (moving) {
            // En marcha: la línea de ahora y la siguiente, grandes y quietas.
            LinearLayout l = new LinearLayout(c);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setGravity(Gravity.CENTER);
            nowLine = CarStyle.text(c, "", 48, CarStyle.TEXT);
            nowLine.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            nowLine.setGravity(Gravity.CENTER);
            nowLine.setMaxLines(2);
            nowLine.setEllipsize(TextUtils.TruncateAt.END);
            nextLine = CarStyle.text(c, "", 34, CarStyle.TEXT_DIM);
            nextLine.setGravity(Gravity.CENTER);
            nextLine.setMaxLines(2);
            nextLine.setEllipsize(TextUtils.TruncateAt.END);
            nextLine.setPadding(0, 24, 0, 0);
            l.addView(nowLine);
            l.addView(nextLine);
            body.addView(l, CarStyle.match());
        } else {
            scroll = new ScrollView(c);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout l = new LinearLayout(c);
            l.setOrientation(LinearLayout.VERTICAL);
            // Margen arriba y abajo: la primera y la última también pueden quedar en el centro.
            l.setPadding(0, host.height() / 3, 0, host.height() / 2);
            for (Lyrics.Line line : lyrics.synced) {
                TextView t = CarStyle.text(c, lineText(line), 36, CarStyle.TEXT_DIM);
                t.setPadding(0, 8, 0, 8);
                t.setGravity(Gravity.CENTER_HORIZONTAL);
                l.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                lineViews.add(t);
            }
            scroll.addView(l);
            body.addView(scroll, CarStyle.match());
        }
        highlight(currentIndex());
    }

    /** Puro: una línea vacía (hueco sin letra) se enseña como «♪». */
    static String lineText(Lyrics.Line l) {
        return l.text.isEmpty() ? "♪" : l.text;
    }

    private int currentIndex() {
        if (lyrics == null || lyrics.synced.isEmpty()) return -1;
        long pos = testPosMs >= 0 ? testPosMs : ctrl == null ? -1 : position(ctrl.getPlaybackState(), SystemClock.elapsedRealtime());
        return pos < 0 ? -1 : Lyrics.indexAt(lyrics.synced, pos);
    }

    /** Puro: por dónde va la canción (ms) con lo último que dijo la app de música, o -1 si no se sabe. */
    static long position(long pos, long updatedAt, float speed, boolean playing, long now) {
        if (pos < 0) return -1;
        if (!playing || updatedAt <= 0) return pos;
        return pos + (long) ((now - updatedAt) * (speed > 0 ? speed : 1f));
    }

    private static long position(PlaybackState s, long now) {
        if (s == null) return -1;
        return position(s.getPosition(), s.getLastPositionUpdateTime(), s.getPlaybackSpeed(), playing(s), now);
    }

    private void highlight(int idx) {
        if (idx == shownIdx) return;
        shownIdx = idx;
        if (nowLine != null) {
            List<Lyrics.Line> l = lyrics.synced;
            nowLine.setText(idx >= 0 ? lineText(l.get(idx)) : "♪");
            nextLine.setText(idx + 1 < l.size() ? lineText(l.get(idx + 1)) : "");
            return;
        }
        for (int i = 0; i < lineViews.size(); i++) {
            TextView t = lineViews.get(i);
            boolean cur = i == idx;
            t.setTextColor(cur ? CarStyle.TEXT : CarStyle.TEXT_DIM);
            t.setAlpha(cur ? 1f : i < idx ? 0.45f : 0.8f);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, CarStyle.textPx(cur ? 44 : 36));
            t.setTypeface(cur ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        }
        if (scroll != null && idx >= 0 && idx < lineViews.size()) {
            TextView cur = lineViews.get(idx);
            ScrollView sv = scroll;
            sv.post(() -> sv.smoothScrollTo(0, Math.max(0, cur.getTop() - sv.getHeight() * 35 / 100)));
        }
    }

    private void renderPlay() {
        if (playButton == null) return;
        boolean p = ctrl != null && playing(ctrl.getPlaybackState());
        playButton.setImageResource(p ? R.drawable.hql_ic_pause : R.drawable.hql_ic_play);
    }

    private void tick() {
        if (destroyed) return;
        long now = SystemClock.elapsedRealtime();
        if (sensors != null && now - lastMoveCheck >= 1000) {
            lastMoveCheck = now;
            CarSensors.Snapshot s = sensors.snapshot();
            boolean m = s.gpsLive() && s.speedKmh >= MOVING_KMH;
            if (m != moving) {
                moving = m;
                if (lyrics != null && !lyrics.synced.isEmpty()) buildSynced();
            }
        }
        if (lyrics != null && !lyrics.synced.isEmpty()) highlight(currentIndex());
        main.postDelayed(tick, TICK_MS);
    }

    // ------------------------------------------------------------------ pruebas

    /** Sin sesiones (pruebas y capturas): esta canción, su letra, por dónde va y si el coche va en marcha. */
    void showForTest(String t, String a, Lyrics.Result r, long posMs, boolean inMotion) {
        title.setText(t);
        artist.setText(a);
        moving = inMotion;
        testPosMs = posMs;
        if (r == null) message(Str.get(R.string.hql_karaoke_searching));
        else show(r);
    }
}
