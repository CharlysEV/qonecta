package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.Collections;
import java.util.List;

/**
 * Reproductor de vídeo del coche, con controles grandes: dos toques en un lado ±10 s, velocidad 1×/1,5×/2×, subtítulos
 * (.srt junto al vídeo, en la carpeta de la app), barra de avance gruesa para el dedo, «¿Seguir desde 12:34?» (marca de
 * 48 h, MediaResume) y, al acabar, el siguiente de la misma lista.
 */
final class VideoPlayer {
    static final long SEEK_MS = 10_000;
    static final long HIDE_MS = 4000;
    static final long SAVE_MS = 5000;
    /** Sin respuesta a «¿Seguir…?» en tanto: sigue. */
    static final long RESUME_ASK_MS = 10_000;
    static final float[] SPEEDS = {1f, 1.5f, 2f};

    private final CarScreen.Host host;
    private final List<MediaLibrary.Item> items;
    private final Runnable onBack;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private MediaFocus focus;
    private int index;
    private int speed;
    private boolean subsOn = true;
    private View controls;
    private TextView time;
    private TextView playBtn;
    private SeekTrack track;

    VideoPlayer(CarScreen.Host host, List<MediaLibrary.Item> items, Runnable onBack) {
        this.host = host;
        this.items = items;
        this.onBack = onBack;
    }

    boolean playing() {
        return player != null;
    }

    private final Runnable hide = () -> {
        if (controls != null) controls.setVisibility(View.GONE);
    };

    private final Runnable tick = new Runnable() {
        private long lastSave;

        @Override
        public void run() {
            if (player == null) return;
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (time != null) time.setText(MediaResume.clock(pos) + (dur > 0 ? " / " + MediaResume.clock(dur) : ""));
            if (track != null && dur > 0) track.set(pos / (float) dur);
            if (playBtn != null) playBtn.setText(player.isPlaying() ? "❚❚" : "▶");
            long now = System.currentTimeMillis();
            if (now - lastSave >= SAVE_MS && player.isPlaying()) {
                lastSave = now;
                save();
            }
            main.postDelayed(this, 500);
        }
    };

    private void save() {
        if (player == null || index < 0 || index >= items.size()) return;
        long dur = player.getDuration();
        if (dur <= 0) return;
        MediaResume.put(host.context(), items.get(index).uri.toString(), player.getCurrentPosition(), dur);
    }

    void open(int i) {
        release();
        index = i;
        Context c = host.context();
        MediaLibrary.Item it = items.get(i);
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(Color.BLACK);
        player = newPlayer(c);
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false);
        focus = MediaFocus.bind(c, player);
        PlayerView pv = CarStyle.player(c);
        pv.setUseController(false);
        pv.setPlayer(player);
        f.addView(pv, CarStyle.match());

        // Toques sobre el vídeo: uno enseña u oculta los controles; dos en un lado, ±10 s.
        View touch = new View(c);
        GestureDetector g = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controls.getVisibility() == View.VISIBLE) hide.run();
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float x = e.getX() / Math.max(1, touch.getWidth());
                if (x < 0.4f) seekBy(-SEEK_MS);
                else if (x > 0.6f) seekBy(SEEK_MS);
                else togglePlay();
                return true;
            }
        });
        touch.setOnTouchListener((v, ev) -> g.onTouchEvent(ev));
        f.addView(touch, CarStyle.match());

        Uri subs = MediaLibrary.subtitles(c, it);
        controls = buildControls(c, it, subs != null);
        // Ocultos hasta que empiece (con «¿Seguir…?» delante no estorban).
        controls.setVisibility(View.GONE);
        f.addView(controls, CarStyle.match());

        MediaItem.Builder mb = new MediaItem.Builder().setUri(it.uri);
        if (subs != null) {
            mb.setSubtitleConfigurations(Collections.singletonList(new MediaItem.SubtitleConfiguration.Builder(subs)
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()));
            L.i("vídeos: con subtítulos");
        }
        player.setMediaItem(mb.build());
        player.setPlaybackParameters(new PlaybackParameters(SPEEDS[speed]));
        applySubs();
        boolean[] audioWarned = {false};
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) ended();
            }

            @Override
            public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                // Audio que el móvil no sabe decodificar (DD+/E-AC-3 en muchos): ExoPlayer lo salta sin decir nada.
                if (audioWarned[0] || !tracks.containsType(C.TRACK_TYPE_AUDIO) || tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) return;
                audioWarned[0] = true;
                L.w("vídeos: sin sonido, el móvil no decodifica su audio (" + audioMimes(tracks) + ")");
                notice(f, Str.get(R.string.hql_video_audio_unsupported, audioMimes(tracks)));
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                L.w("vídeos: error al reproducir (" + e.getErrorCodeName() + ")");
            }
        });
        diagnostics(player);
        player.prepare();
        host.setContent(f);
        host.setFullscreen(true);
        main.post(tick);

        MediaResume.Mark m = MediaResume.get(c, it.uri.toString());
        if (m != null && m.resumable()) {
            askResume(f, m.posMs);
        } else {
            player.play();
            showControls();
        }
    }

    /**
     * Un reproductor con FFmpeg de reserva para el audio (vídeos e IPTV): primero los decodificadores del móvil y, si no
     * saben (AC-3, E-AC-3/DD+, DTS, TrueHD en muchos), FFmpeg. Antes, esos vídeos se veían sin sonido.
     */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static ExoPlayer newPlayer(Context c) {
        Context app = c.getApplicationContext();
        logFfmpeg();
        androidx.media3.exoplayer.DefaultRenderersFactory r = new androidx.media3.exoplayer.DefaultRenderersFactory(app)
                .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        return new ExoPlayer.Builder(app, r).build();
    }

    private static boolean ffmpegLogged;

    /** Una vez por proceso, al log: si FFmpeg está y qué audio sabe (AC-3, E-AC-3…). */
    private static synchronized void logFfmpeg() {
        if (ffmpegLogged) return;
        ffmpegLogged = true;
        L.i("vídeos: FFmpeg para el audio " + ffmpegInfo());
    }

    /**
     * «6.0 (E-AC-3 sí, AC-3 sí, DTS sí; renderizador sí)» o «no disponible». El renderizador es la clase que media3
     * busca por su nombre: si R8 se lo cambiara, FFmpeg no se usaría.
     */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static String ffmpegInfo() {
        try {
            if (!androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()) return "no disponible";
            boolean renderer;
            try {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer");
                renderer = true;
            } catch (ClassNotFoundException e) {
                renderer = false;
            }
            return androidx.media3.decoder.ffmpeg.FfmpegLibrary.getVersion() + " (E-AC-3 "
                    + yes(androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3)) + ", AC-3 "
                    + yes(androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AC3)) + ", DTS "
                    + yes(androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_DTS)) + "; renderizador "
                    + yes(renderer) + ")";
        } catch (Throwable t) {
            return "no disponible (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String yes(boolean b) {
        return b ? "sí" : "no";
    }

    /** Los formatos de las pistas de audio («audio/eac3»), para el aviso y el log. */
    static String audioMimes(androidx.media3.common.Tracks tracks) {
        java.util.LinkedHashSet<String> mimes = new java.util.LinkedHashSet<>();
        for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                String m = g.getTrackFormat(i).sampleMimeType;
                if (m != null) mimes.add(m.replace("audio/", ""));
            }
        }
        return mimes.isEmpty() ? "?" : android.text.TextUtils.join(", ", mimes);
    }

    /** Un aviso sobre el vídeo, arriba, que se quita solo a los NOTICE_MS. */
    private void notice(FrameLayout f, String text) {
        TextView t = CarStyle.text(f.getContext(), text, 24, Color.WHITE);
        t.setBackground(CarStyle.round(0xCC000000, 18));
        t.setPadding(26, 16, 26, 16);
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = 24;
        f.addView(t, lp);
        main.postDelayed(() -> f.removeView(t), NOTICE_MS);
    }

    static final long NOTICE_MS = 8000;

    /**
     * Para el log: con qué decodificador y en qué formato va el vídeo (códec, tamaño, fps; un decodificador
     * «c2.android.*» es por software) y los fotogramas que se pierden. Así se ve por qué un HEVC va a tirones.
     */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static void diagnostics(ExoPlayer p) {
        p.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(EventTime t, String name, long initializedMs, long durationMs) {
                L.i("vídeos: decodificador de vídeo " + name);
            }

            @Override
            public void onAudioDecoderInitialized(EventTime t, String name, long initializedMs, long durationMs) {
                L.i("vídeos: decodificador de audio " + name);
            }

            @Override
            public void onVideoInputFormatChanged(EventTime t, androidx.media3.common.Format f,
                                                  androidx.media3.exoplayer.DecoderReuseEvaluation reuse) {
                L.i(String.format(java.util.Locale.US, "vídeos: %s (%s) %dx%d a %.3f fps", f.sampleMimeType, f.codecs, f.width,
                        f.height, f.frameRate));
            }

            @Override
            public void onDroppedVideoFrames(EventTime t, int dropped, long elapsedMs) {
                L.i("vídeos: " + dropped + " fotogramas perdidos en " + elapsedMs + " ms");
            }
        });
    }

    /** «¿Seguir desde 12:34?»: Seguir / Desde el principio; sin respuesta en RESUME_ASK_MS, sigue. */
    private void askResume(FrameLayout f, long posMs) {
        Context c = host.context();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(36, 28, 36, 28);
        card.setBackground(CarStyle.round(0xF0202124, 32));
        TextView q = CarStyle.text(c, Str.get(R.string.hql_vid_resume_q, MediaResume.clock(posMs)), 30, Color.WHITE);
        card.addView(q);
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, 24, 0, 0);
        TextView yes = CarStyle.pill(c, Str.get(R.string.hql_vid_resume_yes));
        yes.setBackground(CarStyle.accent(28));
        yes.setTextColor(CarStyle.ON_ACCENT);
        TextView no = CarStyle.pill(c, Str.get(R.string.hql_vid_resume_start));
        row.addView(yes);
        row.addView(no);
        card.addView(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        f.addView(card, lp);
        Runnable[] choose = new Runnable[2];
        Runnable auto = () -> choose[0].run();
        choose[0] = () -> {
            main.removeCallbacks(auto);
            f.removeView(card);
            if (player == null) return;
            player.seekTo(posMs);
            player.play();
            showControls();
        };
        choose[1] = () -> {
            main.removeCallbacks(auto);
            f.removeView(card);
            if (player == null) return;
            player.seekTo(0);
            player.play();
            showControls();
        };
        yes.setOnClickListener(v -> choose[0].run());
        no.setOnClickListener(v -> choose[1].run());
        main.postDelayed(auto, RESUME_ASK_MS);
    }

    private View buildControls(Context c, MediaLibrary.Item it, boolean hasSubs) {
        FrameLayout box = new FrameLayout(c);
        box.setBackgroundColor(0x66000000);
        box.setClickable(false);
        // Arriba: volver, título, velocidad y subtítulos.
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(16, 16, 16, 0);
        TextView back = CarStyle.pill(c, "‹  " + Str.get(R.string.hql_back));
        back.setOnClickListener(v -> {
            release();
            host.setFullscreen(false);
            onBack.run();
        });
        top.addView(back);
        TextView title = CarStyle.text(c, it.name, 24, Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(16, 0, 16, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sp = CarStyle.pill(c, speedLabel());
        sp.setOnClickListener(v -> {
            speed = (speed + 1) % SPEEDS.length;
            if (player != null) player.setPlaybackParameters(new PlaybackParameters(SPEEDS[speed]));
            sp.setText(speedLabel());
            showControls();
        });
        top.addView(sp);
        if (hasSubs) {
            TextView cc = CarStyle.pill(c, "CC");
            cc.setAlpha(subsOn ? 1f : 0.5f);
            cc.setOnClickListener(v -> {
                subsOn = !subsOn;
                cc.setAlpha(subsOn ? 1f : 0.5f);
                applySubs();
                showControls();
            });
            top.addView(cc);
        }
        box.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        // Centro: −10 s, reproducir/pausa, +10 s y siguiente.
        LinearLayout mid = new LinearLayout(c);
        mid.setGravity(Gravity.CENTER);
        mid.addView(big(c, "⟲ 10", () -> seekBy(-SEEK_MS)));
        playBtn = big(c, "❚❚", this::togglePlay);
        mid.addView(playBtn);
        mid.addView(big(c, "10 ⟳", () -> seekBy(SEEK_MS)));
        if (index + 1 < items.size()) mid.addView(big(c, "▶▶|", () -> open(index + 1)));
        box.addView(mid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        // Abajo: tiempo y barra gruesa.
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(32, 0, 32, 24);
        time = CarStyle.text(c, "", 24, Color.WHITE);
        bottom.addView(time);
        track = new SeekTrack(c, frac -> {
            if (player != null && player.getDuration() > 0) player.seekTo((long) (frac * player.getDuration()));
            showControls();
        });
        bottom.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64));
        box.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        return box;
    }

    private TextView big(Context c, String s, Runnable r) {
        TextView b = CarStyle.text(c, s, 40, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(36, 20, 36, 20);
        b.setBackground(CarStyle.round(0x88202124, 60));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(24, 0, 24, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            r.run();
            showControls();
        });
        return b;
    }

    private String speedLabel() {
        float s = SPEEDS[speed];
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s).replace('.', ',')) + "×";
    }

    private void showControls() {
        if (controls == null) return;
        controls.setVisibility(View.VISIBLE);
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_MS);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    private void seekBy(long d) {
        if (player == null) return;
        long dur = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + d);
        if (dur > 0) to = Math.min(dur, to);
        player.seekTo(to);
        L.i("vídeos: " + (d > 0 ? "+" : "−") + Math.abs(d / 1000) + " s");
    }

    private void applySubs() {
        if (player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn).build());
    }

    /** Al acabar: visto y, si hay, el siguiente de la lista. */
    private void ended() {
        if (player == null) return;
        long dur = player.getDuration();
        if (dur > 0) MediaResume.put(host.context(), items.get(index).uri.toString(), dur, dur);
        if (index + 1 < items.size()) {
            L.i("vídeos: acabado; pongo el siguiente");
            int nextIndex = index + 1;
            main.postDelayed(() -> {
                if (player != null) open(nextIndex);
            }, 1500);
        }
    }

    void release() {
        main.removeCallbacks(tick);
        main.removeCallbacks(hide);
        if (player != null) {
            save();
            if (focus != null) focus.close();
            focus = null;
            player.release();
            player = null;
        }
    }

    /** Barra de avance gruesa: lo visto en el color de acento; tocar o arrastrar salta ahí. */
    private static final class SeekTrack extends View {
        interface OnSeek {
            void seek(float frac);
        }

        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final OnSeek onSeek;
        private float frac;

        SeekTrack(Context c, OnSeek s) {
            super(c);
            onSeek = s;
            bg.setColor(0x66FFFFFF);
            fg.setColor(CarStyle.ACCENT);
        }

        void set(float f) {
            frac = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = 16;
            float y = (getHeight() - h) / 2f;
            c.drawRoundRect(new RectF(0, y, getWidth(), y + h), h / 2, h / 2, bg);
            c.drawRoundRect(new RectF(0, y, getWidth() * frac, y + h), h / 2, h / 2, fg);
            c.drawCircle(getWidth() * frac, getHeight() / 2f, 22, fg);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                set(Math.max(0f, Math.min(1f, ev.getX() / Math.max(1, getWidth()))));
                return true;
            }
            if (a == MotionEvent.ACTION_UP) {
                onSeek.seek(Math.max(0f, Math.min(1f, ev.getX() / Math.max(1, getWidth()))));
                return true;
            }
            return a == MotionEvent.ACTION_CANCEL;
        }
    }
}
