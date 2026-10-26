package com.headqlink.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * La carga en directo de ahora (una a la vez, mientras viva el proceso): la empieza RoutePlanner al ver que el coche
 * carga, la alimentan las lecturas de la nube (RoutePlanner con la sesión del coche, ChargeWatchService sin ella) y
 * avisa de lo que pasa: «ya puedes seguir» (notificación con sonido y, con el coche, voz y tarjeta en el panel), carga
 * lenta y, al terminar, la guarda en el historial (ChargeLog). Su notificación fija es un Live Update en Android 16
 * (la Now Bar de Samsung, el chip de la barra de estado): el % en grande y la barra hasta el objetivo.
 */
final class ChargeWatch {
    static final String CHANNEL = "hql_charge";
    static final String CHANNEL_ALERT = "hql_charge_alert";
    static final int NOTIF_ID = 21;
    static final int ALERT_ID = 22;

    static final int ENDED_ID = 23;
    /** La carga lenta en curso, guardada entre una comprobación de fondo y la siguiente (el proceso puede morir). */
    private static final String F_LIVE = "charge-live.json";
    private static final String PREFS = "hql_charge_watch";
    /** Una carga se cerró con el coche aún enchufado: no se empieza otra hasta verlo desenchufado. */
    private static final String K_WAIT_UNPLUG = "wait_unplug";

    private static volatile ChargeSession session;
    /** La carga en curso es la de la demostración (vista previa): al salir de ella no debe seguir en el coche de verdad. */
    private static volatile boolean sessionDemo;
    /** Lecturas que había en la última que se guardó (solo se guarda si hay alguna nueva). */
    private static int savedSamples = -1;
    /** El servicio de primer plano está vigilando (carga rápida): la comprobación de fondo no hace nada. */
    static volatile boolean serviceRunning;

    private ChargeWatch() {
    }

    /** La carga en curso, o null. */
    static ChargeSession session() {
        ChargeSession s = session;
        if (s != null && sessionDemo && !DemoMode.active()) {
            // Se salió de la vista previa con su carga de demostración en marcha: fuera.
            session = null;
            sessionDemo = false;
            return null;
        }
        return s == null || s.ended ? null : s;
    }

    /**
     * Empieza una carga. background: también fuera del coche (ChargeWatchService con su notificación); la de la
     * demostración, nunca.
     */
    static void begin(Context ctx, ChargeSession s, boolean background) {
        begin(ctx, s, background, false);
    }

    static void begin(Context ctx, ChargeSession s, boolean background, boolean demo) {
        session = s;
        sessionDemo = demo;
        savedSamples = -1;
        L.i(String.format(Locale.US, "carga en directo: empieza al %.1f %% (%s%s)%s%s", s.startSoc, s.dc ? "CC" : "CA",
                s.charger == null ? "" : ", " + s.charger.name + " " + Math.round(s.charger.maxKw) + " kW"
                        + (s.charger.maxVolts > 0 ? " " + Math.round(s.charger.maxVolts) + " V" : ""),
                background ? "; vigilada también fuera del coche" : "", s.seenLate ? "; vista ya empezada (comprobación de fondo)" : ""));
        if (!background || android.os.Build.VERSION.SDK_INT < 26) return;
        try {
            ctx.startForegroundService(new Intent(ctx, ChargeWatchService.class));
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException u otra restricción: queda el aviso en el coche.
            L.w("carga en directo: Android no deja vigilarla fuera del coche (" + e.getClass().getSimpleName() + "); solo en el coche");
        }
    }

    /** Una lectura de la nube (repetidas no cuentan): avisa de lo que pase. Desde cualquier hilo. */
    static void tick(Context ctx, CarCloud.Snapshot cs) {
        ChargeSession s = session;
        if (s == null || s.ended || cs == null || !cs.hasData()) return;
        if (s.demo != cs.demo) {
            // Se acabó la demostración (o empezó con una carga real): esa carga no sigue con los otros datos.
            synchronized (s) {
                s.ended = true;
            }
            if (session == s) session = null;
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
            L.i("carga en directo: termina (" + (s.demo ? "fin de la demostración" : "empieza la demostración") + ")");
            return;
        }
        LeapStatus st = cs.status;
        List<ChargeSession.Event> ev = s.observe(cs.dataTimeMs(), st.socBest(), st.powerKw(), st.pluggedIn(),
                Boolean.TRUE.equals(st.chargeCompleted) && !st.charging(), st.chargeRemainMin, DemoMode.wallClockMs());
        for (ChargeSession.Event e : ev) handle(ctx.getApplicationContext(), s, e, cs);
        if (!s.demo && !s.ended) save(ctx.getApplicationContext(), s);
    }

    // ------------------------------------------------------------------ empezar, y sin la app abierta

    /**
     * ¿Se empieza una carga con esta lectura? Cargando de verdad, con un dato reciente (el de un coche dormido no lo es)
     * y sin otra cerrada con el coche aún enchufado (18-10: el coche al 100 % y dormido empezaba y cerraba una cada 5 s).
     */
    static boolean mayStart(Context ctx, CarCloud.Snapshot cs) {
        if (cs == null || !cs.hasData() || session() != null) return false;
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!cs.status.pluggedIn() && sp.getBoolean(K_WAIT_UNPLUG, false)) sp.edit().putBoolean(K_WAIT_UNPLUG, false).apply();
        return cs.status.charging() && !Double.isNaN(cs.status.socBest()) && !sp.getBoolean(K_WAIT_UNPLUG, false)
                && RoutePlanner.freshForCharge(DemoMode.wallClockMs(), cs.dataTimeMs());
    }

    /** Una carga nueva con esta lectura (at: el cargador si se sabe). */
    static ChargeSession newSession(CarCloud.Snapshot cs, RoutePlanner.Charger at) {
        double cap = cs.capacityKwh > 0 ? cs.capacityKwh : EnergyModel.usableKwh();
        return new ChargeSession(cs.dataTimeMs(), cs.status.socBest(), cs.status.dcPlugged(), at, cap, RoutePlanner.carPeakKw(cap),
                RoutePlanner.carVolts(cap));
    }

    /**
     * La comprobación de fondo (ChargeCheckJob, cada 15 min sin la app abierta) con una lectura de la nube: empieza la
     * carga si la ve (rápida: la vigila el servicio de siempre; lenta: la siguen estas comprobaciones, con una
     * notificación) o sigue la lenta en curso.
     */
    static void background(Context ctx, CarCloud.Snapshot cs) {
        restore(ctx);
        if (serviceRunning || cs == null || !cs.hasData() || cs.demo) return;
        ChargeSession s = session();
        if (s == null && mayStart(ctx, cs)) {
            s = newSession(cs, null);
            s.seenLate = true;
            begin(ctx, s, true);
        }
        if (s == null) return;
        tick(ctx, cs);
        // Sin el servicio (Android no lo dejó arrancar o le agotó el tiempo): la notificación, al día con esta lectura.
        if (!s.ended && !serviceRunning && android.os.Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager.class).notify(NOTIF_ID, ongoing(ctx));
        }
    }

    private static File liveFile(Context ctx) {
        return new File(ctx.getFilesDir(), F_LIVE);
    }

    /** Guarda la carga en curso (solo si hay alguna lectura nueva). */
    private static void save(Context ctx, ChargeSession s) {
        int n;
        synchronized (s) {
            n = s.samples.size();
        }
        if (n == savedSamples) return;
        savedSamples = n;
        try {
            org.json.JSONObject o = s.toJson();
            o.put("remain", Double.isNaN(s.carRemainMin) ? org.json.JSONObject.NULL : s.carRemainMin);
            java.nio.file.Files.write(liveFile(ctx).toPath(), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            L.w("carga en directo: no se pudo guardar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** La carga en curso de antes (el proceso murió entre dos comprobaciones), si la hay y aún no se sabe de otra. */
    static void restore(Context ctx) {
        if (session != null) return;
        File f = liveFile(ctx);
        if (!f.isFile()) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            ChargeSession s = ChargeSession.fromJson(o);
            s.ended = false;
            s.endMs = 0;
            s.endSoc = Double.NaN;
            s.carRemainMin = o.optDouble("remain", Double.NaN);
            session = s;
            sessionDemo = false;
            savedSamples = s.samples.size();
            L.i(String.format(Locale.US, "carga en directo: sigo la de antes (%s, %d lecturas)", s.dc ? "CC" : "CA", savedSamples));
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void handle(Context ctx, ChargeSession s, ChargeSession.Event e, CarCloud.Snapshot cs) {
        switch (e) {
            case READY: {
                String title = Str.get(R.string.hql_live_ready_title);
                String text = readyText(s);
                L.i(String.format(Locale.US, "carga en directo: lista al %.1f %% (objetivo %.1f %%)", s.soc(), s.targetPct));
                alert(ctx, title, text, RoutePlanner.ALERT_READY);
                break;
            }
            case SLOW: {
                String title = Str.get(R.string.hql_live_slow_title);
                String text = Str.get(R.string.hql_live_slow_text, s.slowKw, s.slowExpectedKw);
                L.w(String.format(Locale.US, "carga en directo: lenta (%.0f kW; esperaba ~%.0f)", s.slowKw, s.slowExpectedKw));
                alert(ctx, title, text, RoutePlanner.ALERT_WARN);
                break;
            }
            case ENDED: {
                L.i(String.format(Locale.US, "carga en directo: termina al %.1f %% (+%.1f kWh en %d min, %d lecturas)%s", s.endSoc,
                        s.kwhAdded(), (s.endMs - s.startMs) / 60_000L, s.samples.size(), s.readyFired ? "" : " sin llegar al objetivo"));
                ChargeLog.add(ctx, s);
                if (session == s) session = null;
                //noinspection ResultOfMethodCallIgnored
                liveFile(ctx).delete();
                // Cerrada con el coche aún enchufado: hasta desenchufarlo no se empieza otra.
                if (cs.status != null && cs.status.pluggedIn() && !cs.demo) {
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_WAIT_UNPLUG, true).apply();
                }
                ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
                // La lenta (en casa, horas): un aviso discreto de cómo ha ido. La rápida ya avisó al estar lista.
                if (!s.dc && !s.demo && s.endSoc > s.startSoc + 0.5) ended(ctx, s);
                break;
            }
        }
    }

    /** «Carga terminada · 54 % → 100 % · +38,2 kWh en 5 h 10 min», en el canal discreto (sin sonido). */
    private static void ended(Context ctx, ChargeSession s) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_live_channel), NotificationManager.IMPORTANCE_LOW));
        String text = endedText(s);
        nm.notify(ENDED_ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(ctx.getColor(R.color.hql_accent))
                .setContentTitle(Str.get(R.string.hql_live_ended_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build());
    }

    /** «54 % → 100 % · +38,2 kWh en 5 h 10 min» (y «vista desde las 21:40» si se vio empezada). */
    static String endedText(ChargeSession s) {
        String t = Str.get(R.string.hql_live_ended_text, s.startSoc, s.endSoc, s.kwhAdded(),
                DriveTab.duration(Math.max(0, (s.endMs - s.startMs) / 1000)));
        return s.seenLate ? t + " · " + Str.get(R.string.hql_live_seen_late, time(s.startMs)) : t;
    }

    /** «64 % · llegas a Zunder Lleida con 12 %» y, si se puede, «hasta 85 % te ahorras la parada de …». */
    static String readyText(ChargeSession s) {
        StringBuilder b = new StringBuilder();
        if (!Double.isNaN(s.nextArrivePct) && !s.nextName.isEmpty()) {
            b.append(Str.get(R.string.hql_live_ready_text, s.soc(), s.nextName, s.nextArrivePct));
        } else {
            b.append(Str.get(R.string.hql_live_ready_short, s.soc()));
        }
        // En el aviso (y por voz) solo si compensa; en la pantalla siempre (en gris si no).
        String skip = s.skipWorth() ? skipText(s) : "";
        if (!skip.isEmpty()) b.append(". ").append(skip);
        return b.toString();
    }

    /** «Hasta 85 % te ahorras la parada de Lleida (+7 min aquí, −23 min allí)», o vacío. */
    static String skipText(ChargeSession s) {
        if (Double.isNaN(s.skipPct) || s.skipName.isEmpty()) return "";
        return Str.get(R.string.hql_live_skip, s.skipPct, s.skipName, DriveTab.duration(Math.round(s.skipExtraMin * 60)),
                DriveTab.duration(Math.round(s.skipSavedMin * 60)));
    }

    /** Notificación con sonido; con la sesión del coche, también la tarjeta del panel y la voz (si está activada). */
    private static void alert(Context ctx, String title, String text, int kind) {
        RoutePlanner.ChargeAlertListener l = RoutePlanner.chargeAlerts;
        if (l != null) {
            l.onChargeAlert(title, text, kind);
            if (new Config(ctx).planVoice()) VoiceAlert.say(ctx, title + ". " + text);
        }
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, Str.get(R.string.hql_live_alert_channel),
                NotificationManager.IMPORTANCE_HIGH));
        nm.notify(ALERT_ID, new Notification.Builder(ctx, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(ctx.getColor(kind == RoutePlanner.ALERT_READY ? R.color.hql_accent : R.color.hql_warn))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build());
    }

    static PendingIntent openApp(Context ctx) {
        return PendingIntent.getActivity(ctx, 0, new Intent(ctx, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
    }

    /** Cada cuánto se lee la nube vigilando esta carga: la rápida, como con la sección Coche a la vista; la lenta, 5 min. */
    static long watchMs(ChargeSession s) {
        if (s == null) return 0;
        return s.dc ? CarCloud.Policy.HUB_MS : CarCloud.Policy.WATCH_AC_MS;
    }

    /**
     * Hasta dónde llega la barra: el % del plan de ruta si lo hay; si no, el 80 % en la rápida (a partir de ahí va lenta)
     * y el 100 % en la lenta. Pasado el 80 % en una rápida sin plan, el 100 %.
     */
    static double barTarget(ChargeSession s) {
        if (!Double.isNaN(s.targetPct) && s.targetPct > s.startSoc) return Math.min(100, s.targetPct);
        if (s.dc && s.soc() < DC_BAR_PCT - 0.5) return DC_BAR_PCT;
        return 100;
    }

    static final double DC_BAR_PCT = 80;

    /** El % para el chip de la barra de estado (Android 16): «54 %». */
    static String chip(ChargeSession s) {
        return String.format(Locale.getDefault(), "%.0f %%", s.soc());
    }

    /** La notificación fija de la carga (Android 8+): «Cargando · 54 % → 64 %», «85 kW · lista ≈ 14:32», con la barra. */
    @android.annotation.TargetApi(26)
    static Notification ongoing(Context ctx) {
        return ongoing(ctx, session());
    }

    @android.annotation.TargetApi(26)
    static Notification ongoing(Context ctx, ChargeSession s) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_live_channel), NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(ctx.getColor(R.color.hql_accent))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx));
        if (s == null) {
            return b.setContentTitle(Str.get(R.string.hql_live_title_wait)).build();
        }
        b.setContentTitle(title(s)).setContentText(line(s));
        double target = barTarget(s);
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            liveUpdate(ctx, b, s, target);
        } else if (target > s.startSoc) {
            int p = (int) Math.round(Math.max(0, Math.min(1, (s.soc() - s.startSoc) / (target - s.startSoc))) * 100);
            b.setProgress(100, p, false);
        }
        return b.build();
    }

    /**
     * Android 16: Live Update (se pide que suba arriba: Now Bar, chip con el % en la barra de estado, pantalla de
     * bloqueo). La barra es la batería, de 0 a 100: en color hasta el objetivo, con una marca en él.
     */
    @android.annotation.TargetApi(36)
    private static void liveUpdate(Context ctx, Notification.Builder b, ChargeSession s, double target) {
        int t = (int) Math.round(Math.max(1, Math.min(100, target)));
        int accent = ctx.getColor(R.color.hql_accent);
        Notification.ProgressStyle ps = new Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress((int) Math.round(Math.max(0, Math.min(100, s.soc()))))
                .addProgressSegment(new Notification.ProgressStyle.Segment(t).setColor(accent));
        if (t < 100) {
            ps.addProgressSegment(new Notification.ProgressStyle.Segment(100 - t).setColor(ctx.getColor(R.color.hql_idle)));
            ps.addProgressPoint(new Notification.ProgressStyle.Point(t).setColor(ctx.getColor(R.color.hql_ok)));
        }
        b.setStyle(ps)
                .setShortCriticalText(chip(s));
        android.os.Bundle x = new android.os.Bundle();
        x.putBoolean(EXTRA_PROMOTED, true);
        b.addExtras(x);
    }

    /**
     * Variante de pruebas («carga demo»): la notificación de una carga rápida inventada (54 % → 80 %, 85 kW), para ver el
     * Live Update en el móvil sin cargar de verdad; «carga off» la quita.
     */
    static String labNotify(Context ctx, boolean on) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (!on) {
            nm.cancel(LAB_ID);
            return "notificación de carga quitada";
        }
        if (android.os.Build.VERSION.SDK_INT < 26) return "no: Android 8 o más";
        long now = System.currentTimeMillis();
        ChargeSession s = new ChargeSession(now - 8 * 60_000L, 34, true, null, 81.9, 180, 800);
        double[][] pts = {{34, 120}, {38.9, 135}, {44.4, 130}, {49.7, 110}, {54.2, 85}};
        for (int i = 0; i < pts.length; i++) {
            long t = now - (pts.length - 1 - i) * 2 * 60_000L;
            s.observe(t, pts[i][0], pts[i][1], true, false, Double.NaN, t);
        }
        nm.notify(LAB_ID, ongoing(ctx, s));
        boolean promo = android.os.Build.VERSION.SDK_INT >= 36 && nm.canPostPromotedNotifications();
        return "notificación de carga puesta (Live Update " + (promo ? "permitido" : "no permitido o Android < 16") + ")";
    }

    private static final int LAB_ID = 29;

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1; en 36.0 lo lee igual el sistema). */
    static final String EXTRA_PROMOTED = "android.requestPromotedOngoing";

    /** «Cargando · 54 % → 64 %», «Lista: puedes seguir · 66 %» o «Cargando · 54 %». */
    static String title(ChargeSession s) {
        if (s.readyFired) return Str.get(R.string.hql_live_title_ready, s.soc());
        if (!Double.isNaN(s.targetPct)) return Str.get(R.string.hql_live_title_target, s.soc(), s.targetPct);
        return Str.get(R.string.hql_live_title, s.soc());
    }

    /** «85 kW · lista ≈ 14:32 (12 min)» o, sin objetivo, «7 kW · llena ≈ 18:40 (según el coche)». */
    static String line(ChargeSession s) {
        ChargeSession.Sample l = s.last();
        StringBuilder b = new StringBuilder();
        if (l != null && !Double.isNaN(l.kw)) b.append(String.format(Locale.getDefault(), "%.0f kW", l.kw));
        String w = when(s);
        if (!w.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(w);
        return b.toString();
    }

    /**
     * «lista ≈ 14:32 (12 min)», «llena ≈ 18:40 (según el coche)», sin plan «80 % ≈ 14:32 (25 min)» (hasta donde llega la
     * barra), o vacío (ya lista o sin saberlo).
     */
    static String when(ChargeSession s) {
        if (s.readyFired) return "";
        double at = s.readyAtMs();
        if (Double.isNaN(s.targetPct) && (Double.isNaN(at) || s.dc)) {
            double to = barTarget(s);
            double t = s.atMs(to);
            if (Double.isNaN(t) || s.soc() >= to - 0.25) return "";
            long left = Math.max(0, Math.round((t - DemoMode.wallClockMs()) / 1000));
            return Str.get(R.string.hql_live_pct_at, to, time((long) t), DriveTab.duration(left));
        }
        if (Double.isNaN(at)) return "";
        String hhmm = time((long) at);
        if (Double.isNaN(s.targetPct)) return Str.get(R.string.hql_live_full_at, hhmm);
        long left = Math.max(0, Math.round((at - DemoMode.wallClockMs()) / 1000));
        return Str.get(R.string.hql_live_ready_at, hhmm, DriveTab.duration(left));
    }

    static String time(long ms) {
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Str.locale()).format(new java.util.Date(ms));
    }
}
