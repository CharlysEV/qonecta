package com.headqlink.link;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;

/**
 * La cuenta de Leapmotor en el proceso: un solo cliente (LeapApi) para el sondeo del coche y para la pantalla del
 * móvil, con todo serializado (dos refrescos a la vez gastarían el refreshToken). Guarda la sesión cifrada cada vez
 * que cambia (login o token refrescado).
 */
final class CarCloudSession {
    /** Falta algo por configurar (certificado, sesión o coche). */
    static final class NotConfiguredException extends IOException {
        NotConfiguredException(String what) {
            super(what);
        }
    }

    /** Antes de pedir cada página: false para no pedir más (el tope diario de lecturas). */
    interface PageGate {
        boolean take();
    }

    private static LeapApi api;
    private static CarCloudStore.Saved saved;
    /** Pedido de olvidar el cliente (sin esperar al candado: lo pide la interfaz mientras el sondeo puede estar leyendo). */
    private static volatile boolean stale;
    /** Transporte de las pruebas (null: el real). */
    private static LeapApi.Transport testTransport;
    /** El de la aplicación (para el despertador de la renovación). */
    private static Context appCtx;

    private CarCloudSession() {
    }

    /** Solo pruebas: otro transporte (y se olvida el cliente actual). */
    static synchronized void setTransportForTest(LeapApi.Transport t) {
        testTransport = t;
        api = null;
        saved = null;
        stale = false;
        refreshFailedAtMs = 0;
        loggedToken = null;
    }

    /** El almacén (y se apunta el contexto de la aplicación para el despertador). */
    private static CarCloudStore store(Context ctx) {
        Context app = ctx.getApplicationContext();
        appCtx = app != null ? app : ctx;
        return new CarCloudStore(ctx);
    }

    /**
     * Se olvida el cliente (certificado nuevo, clave del servidor aceptada, sesión cerrada): se rehace al usarlo. No
     * espera al candado (se llama desde la interfaz): lo aplica el siguiente uso.
     */
    static void invalidate() {
        stale = true;
    }

    private static LeapApi.Transport transport(CarCloudStore st) {
        if (testTransport != null) return testTransport;
        return new LeapHttps(new LeapTls.PinTrust(st.acceptedPins()));
    }

    private static LeapApi newApi(CarCloudStore st, String deviceId) throws IOException, GeneralSecurityException {
        LeapTls.Identity id;
        try {
            id = st.identity();
        } catch (GeneralSecurityException e) {
            // El Keystore aún sin arrancar o un fallo suyo (tras actualizar el sistema): se reintenta, no se da por perdido.
            if (!CarCloudStore.lostForGood(e)) throw new IOException("Keystore: " + e.getClass().getSimpleName());
            // La clave del Keystore ya no abre lo guardado (se borró, o el móvil se restauró): hay que volver a configurarlo.
            throw new NotConfiguredException("certificado guardado ilegible (" + e.getClass().getSimpleName() + ")");
        }
        if (id == null) throw new NotConfiguredException("sin certificado de cliente");
        return new LeapApi(transport(st), id, System::currentTimeMillis, new SecureRandom(), deviceId);
    }

    /**
     * Cliente con la sesión guardada (con el token renovado si caduca pronto), o NotConfigured si falta algo. Si ya se
     * sabe que hay que volver a entrar, SessionExpired sin pedir nada a la nube.
     */
    private static void ensure(CarCloudStore st) throws IOException, GeneralSecurityException {
        if (stale) {
            stale = false;
            api = null;
            saved = null;
        }
        String why = st.reloginReason();
        if (why != null) {
            api = null;
            saved = null;
            throw new LeapApi.SessionExpiredException("hay que volver a entrar: " + why);
        }
        if (api == null || !api.loggedIn() || saved == null) load(st);
        refreshIfDue(st);
    }

    private static void load(CarCloudStore st) throws IOException, GeneralSecurityException {
        CarCloudStore.Saved s;
        try {
            s = st.session();
        } catch (GeneralSecurityException e) {
            String what = "sesión guardada ilegible (" + e.getClass().getSimpleName() + ")";
            // El Keystore ocupado o sin arrancar (tras una actualización): se reintenta; la sesión no se toca.
            if (!CarCloudStore.lostForGood(e)) throw new IOException(what + ", se reintenta");
            // Otra clave o el fichero estropeado: no se borra nada, pero hay que volver a entrar (y se dice así, no
            // «falta algo»: antes la cuenta seguía como «iniciada» y nada funcionaba).
            st.markRelogin(what);
            L.w("nube Leapmotor: " + what + "; hay que volver a entrar en el móvil (no se borra nada)");
            refreshWidget();
            throw new LeapApi.SessionExpiredException(what);
        }
        if (s == null || s.session == null || !s.session.usable()) throw new NotConfiguredException("sin sesión");
        LeapApi a = newApi(st, s.session.deviceId);
        a.restore(s.session);
        api = a;
        saved = s;
        // Con la app recién arrancada (tras actualizarla o reiniciar el móvil), el despertador de este token.
        arm(s.session.token);
    }

    /**
     * La nube rechaza la sesión (la renovación del token, o no hay con qué): se apunta, con el porqué en el log, y no se
     * le vuelve a pedir nada hasta entrar de nuevo (ni renovaciones en bucle). La sesión guardada no se borra.
     */
    private static LeapApi.SessionExpiredException closed(CarCloudStore st, LeapApi.SessionExpiredException e) {
        LeapApi.Session s = api == null ? null : api.session();
        long exp = s == null ? 0 : LeapCrypto.tokenExpiryMs(s.token);
        api = null;
        saved = null;
        if (st.reloginReason() != null) return e;
        st.markRelogin(e.getMessage());
        long now = System.currentTimeMillis();
        String age = exp <= 0 ? "" : exp <= now
                ? String.format(java.util.Locale.US, "; el token había caducado hace %.1f h", (now - exp) / 3_600_000.0)
                : String.format(java.util.Locale.US, "; al token le quedaban %d min", (exp - now) / 60_000);
        L.w("nube Leapmotor: sesión cerrada por la nube (" + e.getMessage() + ")" + age
                + "; hay que volver a entrar en el móvil (la sesión guardada se conserva)");
        disarm();
        refreshWidget();
        return e;
    }

    /** El widget, al momento: «Sesión de Leapmotor cerrada…» en la fila de las órdenes. */
    private static void refreshWidget() {
        Context c = appCtx;
        if (c == null) return;
        try {
            CarOrders.refresh(c);
        } catch (RuntimeException ignored) {
            // Sin widgets, o el launcher no contesta: se verá en el siguiente repintado.
        }
    }

    private static void persist(CarCloudStore st) throws IOException, GeneralSecurityException {
        if (api == null || saved == null) return;
        saved.session = api.session();
        st.saveSession(saved);
        if (saved.session == null) return;
        // Se vuelve a entrar como el mismo móvil aunque se cierre la sesión (como la app oficial).
        st.rememberDeviceId(saved.session.deviceId);
        // Para el log, una vez por token: cuándo caduca (para saber si la nube lo rechaza por caducado o antes) y, si el
        // de renovación también lo dice, hasta cuándo vale renovar.
        String tok = saved.session.token;
        if (tok != null && !tok.equals(loggedToken)) {
            loggedToken = tok;
            long now = System.currentTimeMillis();
            long exp = LeapCrypto.tokenExpiryMs(tok);
            long rexp = LeapCrypto.tokenExpiryMs(saved.session.refreshToken);
            if (exp > 0) {
                L.i(String.format(java.util.Locale.US, "nube Leapmotor: token en uso, caduca en %.1f h", (exp - now) / 3_600_000.0)
                        + (rexp > 0 ? String.format(java.util.Locale.US, " (el de renovación, en %.1f h)", (rexp - now) / 3_600_000.0) : ""));
            }
            arm(tok);
        }
    }

    /** El último token del que se apuntó la caducidad. */
    private static String loggedToken;

    /** Se renueva el token cuando le queda menos de esto (antes, solo cuando la nube ya lo rechazaba, a veces horas tarde). */
    static final long REFRESH_AHEAD_MS = 60 * 60_000;
    /**
     * Tras una renovación fallida (sin red, o rechazada con el token aún válido), no se vuelve a intentar antes de esto:
     * el sondeo lee cada 90 s y cada lectura lo intentaría (un bucle de renovaciones).
     */
    static final long REFRESH_RETRY_MS = 15 * 60_000;
    /** Cuándo falló la última renovación (ms de pared; 0: no ha fallado). */
    private static long refreshFailedAtMs;

    /** Puro: ¿toca renovar ya? expMs: cuándo caduca el token (ms; 0 si no se sabe: no se toca). */
    static boolean refreshDue(long expMs, long nowMs) {
        return expMs > 0 && expMs - nowMs <= REFRESH_AHEAD_MS;
    }

    /**
     * Con el cliente cargado: renueva el token si caduca pronto. true si lo renovó. Si falla con el token aún válido, se
     * sigue con él (y no se reintenta hasta REFRESH_RETRY_MS); si ya había caducado y la nube lo rechaza, la sesión se
     * da por cerrada (closed).
     */
    private static boolean refreshIfDue(CarCloudStore st) throws IOException, GeneralSecurityException {
        LeapApi.Session s = api.session();
        long now = System.currentTimeMillis();
        long exp = s == null ? 0 : LeapCrypto.tokenExpiryMs(s.token);
        if (!refreshDue(exp, now) || now - refreshFailedAtMs < REFRESH_RETRY_MS) return false;
        if (exp <= now) {
            // Para el log de un usuario: nadie lo renovó a tiempo (Android no despertó la app, o estuvo parada).
            L.w(String.format(java.util.Locale.US, "nube Leapmotor: el token había caducado hace %.1f h sin renovarse",
                    (now - exp) / 3_600_000.0));
        }
        try {
            api.refresh();
        } catch (LeapHttps.ServerKeyChangedException e) {
            throw e;
        } catch (LeapApi.SessionExpiredException e) {
            refreshFailedAtMs = now;
            if (exp <= now) throw closed(st, e);
            // Aún vale un rato: se sigue con él (el log lleva lo que contestó la nube, LeapApi.refresh).
            L.w("nube Leapmotor: no se pudo renovar antes de caducar (" + e.getMessage() + "); el token vale aún "
                    + (exp - now) / 60_000 + " min");
            return false;
        } catch (IOException e) {
            refreshFailedAtMs = now;
            L.w("nube Leapmotor: no se pudo renovar el token (" + Http.safeError(e) + "); otra vez en "
                    + REFRESH_RETRY_MS / 60_000 + " min");
            return false;
        }
        refreshFailedAtMs = 0;
        persist(st);
        L.i("nube Leapmotor: token renovado antes de caducar");
        return true;
    }

    // ------------------------------------------------------------------ despertador de la renovación

    private static final int RC_ALARM = 4212;

    /**
     * Puro: cuándo despertar para renovar un token que caduca en expMs: en cuanto toque (REFRESH_AHEAD_MS antes, y un
     * minuto de margen), pero nunca antes de REFRESH_RETRY_MS desde ahora (un token raro o el reloj cambiado no hacen un
     * bucle de despertares). 0: el token no dice cuándo caduca.
     */
    static long alarmAtMs(long expMs, long nowMs) {
        if (expMs <= 0) return 0;
        return Math.max(expMs - REFRESH_AHEAD_MS + 60_000, nowMs + REFRESH_RETRY_MS);
    }

    private static PendingIntent alarmIntent(Context c) {
        return PendingIntent.getBroadcast(c, RC_ALARM, new Intent(c, RefreshAlarm.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Un despertador (también con el móvil en reposo profundo) para renovar el token antes de que caduque: la
     * comprobación de cada 15 min (ChargeCheckJob) puede esperar horas con el móvil quieto o con un fabricante que
     * aprieta (Vivo, Xiaomi…), y el token dura 2 h. Uno solo a la vez (sustituye al anterior): unos 24 al día como mucho.
     */
    private static void arm(String token) {
        Context c = appCtx;
        long at = alarmAtMs(LeapCrypto.tokenExpiryMs(token), System.currentTimeMillis());
        if (c == null || at <= 0 || android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c));
        } catch (RuntimeException e) {
            L.w("nube Leapmotor: sin despertador para renovar el token (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void disarm() {
        Context c = appCtx;
        if (c == null || android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am != null) am.cancel(alarmIntent(c));
        } catch (RuntimeException ignored) {
        }
    }

    /** El despertador: renueva el token si toca (keepAlive, con el mismo candado que todo lo demás). */
    public static final class RefreshAlarm extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            Context app = c.getApplicationContext();
            PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    L.init(app);
                    keepAlive(app);
                } finally {
                    pr.finish();
                }
            }, "hql-leap-refresh").start();
        }
    }

    /**
     * Mantiene la sesión viva sin abrir la app: la comprobación de fondo (ChargeCheckJob, ~15 min) la llama y el token se
     * renueva antes de caducar, para no tener que volver a poner la contraseña tras unas horas sin leer el coche.
     */
    static synchronized void keepAlive(Context ctx) {
        try {
            CarCloudStore st = store(ctx);
            if (!st.hasSession()) return;
            ensure(st);
            // El despertador del token en uso (si no se renovó ahora, el de siempre; si falló, dentro de 15 min).
            if (api != null && api.session() != null) arm(api.session().token);
        } catch (NotConfiguredException | LeapApi.SessionExpiredException ignored) {
            // Sin cuenta, o hay que volver a entrar (ya está en el log una vez): nada que mantener.
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            L.w("nube Leapmotor: mantener la sesión: " + Http.safeError(e));
        }
    }

    /** Estado del coche elegido (lo usan el sondeo y «Leer estado ahora»). */
    static synchronized LeapStatus readStatus(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        JSONObject data;
        try {
            data = api.status(saved.vin, saved.carType);
        } catch (LeapApi.SessionExpiredException e) {
            throw closed(st, e);
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
        if (com.andrerinas.openheadunit.BuildConfig.LAB) dumpSignals(data);
        return LeapStatus.parse(data);
    }

    /** Solo en pruebas: vuelca al log las señales del coche (sin posición) cada 3 min, para buscar las que no usamos. */
    private static long lastSignalDumpMs;

    private static void dumpSignals(JSONObject data) {
        long now = System.currentTimeMillis();
        if (now - lastSignalDumpMs < 180_000L) return;
        lastSignalDumpMs = now;
        L.i("nube Leapmotor [pruebas]: " + LeapStatus.rawSignalsDump(data));
    }

    /**
     * Viajes del coche entre fromS y toS (segundos de época) del historial de la nube, como mucho maxPages páginas de 20.
     * pageRead se llama antes de cada página (el sondeo cuenta ahí su tope diario; si devuelve false, se para).
     */
    static synchronized List<CloudHistory.Trip> readTrips(Context ctx, long fromS, long toS, int maxPages,
                                                        PageGate pageRead)
            throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        List<CloudHistory.Trip> out = new java.util.ArrayList<>();
        try {
            int pages = 1;
            for (int page = 1; page <= pages; page++) {
                if (!pageRead.take()) break;
                JSONObject d = api.tripsPage(saved.vin, fromS, toS, page);
                out.addAll(CloudHistory.parsePage(d));
                if (page == 1) pages = CloudHistory.pages(d, maxPages);
            }
        } catch (LeapApi.SessionExpiredException e) {
            throw closed(st, e);
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
        return out;
    }

    /**
     * Una orden del widget «Coche» (CarCommand), con el PIN guardado. true si el coche la confirma; false si no contesta a
     * tiempo. Para el clima a una temperatura, la del interior del coche (último dato) dice si calienta o enfría.
     */
    static synchronized boolean command(Context ctx, CarCommand c) throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        String pin = st.pin();
        if (pin == null || pin.isEmpty()) throw new NotConfiguredException("sin PIN del coche");
        CarCloud.Snapshot snap = CarCloud.snapshot();
        double inside = snap != null && snap.hasData() && !snap.demo ? snap.status.interiorTempC : Double.NaN;
        int v0 = api.sessionVersion();
        try {
            boolean ok = api.remote(saved.vin, c.cmdId(), c.content(saved.carType, inside), pin);
            L.i("nube Leapmotor: orden " + c + (ok ? " (confirmada por el coche)" : " (sin confirmar)")
                    + (api.lastRemoteRetries > 0 ? " tras " + api.lastRemoteRetries + " reintentos sin red" : ""));
            return ok;
        } catch (LeapApi.SessionExpiredException e) {
            throw closed(st, e);
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    /** Dónde está el coche, para «Localizar»: la posición y la hora del dato (ms de pared; 0 si no se sabe). */
    static final class Found {
        final LeapStatus.Position position;
        final long dataMs;

        Found(LeapStatus.Position position, long dataMs) {
            this.position = position;
            this.dataMs = dataMs;
        }
    }

    /**
     * «Localizar»: una lectura del estado solo para la posición del coche, o null si no la da. No se guarda ni va al
     * registro (ni el estado leído pasa a CarCloud).
     */
    static synchronized Found readPosition(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        try {
            JSONObject data = api.status(saved.vin, saved.carType);
            LeapStatus.Position p = LeapStatus.position(data);
            L.i("nube Leapmotor: «Localizar»: " + (p == null ? "el coche no da su posición" : "posición leída (no se guarda)"));
            return p == null ? null : new Found(p, LeapStatus.dataTime(LeapStatus.merge(data)));
        } catch (LeapApi.SessionExpiredException e) {
            throw closed(st, e);
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    /** El reparto de la energía de una ventana según el coche, sin los km: una sola lectura. */
    static synchronized CloudUsage.Split readSplit(Context ctx, long fromMs, long toMs)
            throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        try {
            return CloudUsage.parseSplit(api.energySplit(saved.vin, fromMs / 1000, toMs / 1000));
        } catch (LeapApi.SessionExpiredException e) {
            throw closed(st, e);
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    /**
     * «Consumo» de una ventana según el coche (28-10): los km y el reparto de la energía (conducción, clima, otros). Si
     * una de las dos lecturas falla, la otra vale sola; si fallan las dos, el error.
     */
    static synchronized CloudUsage.Window readWindow(Context ctx, long fromMs, long toMs)
            throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        try {
            IOException first = null;
            double km = Double.NaN;
            CloudUsage.Split split = null;
            try {
                km = CloudUsage.parseKm(api.mileage(saved.vin, fromMs, toMs));
            } catch (LeapApi.SessionExpiredException e) {
                throw e;
            } catch (IOException e) {
                first = e;
            }
            try {
                split = CloudUsage.parseSplit(api.energySplit(saved.vin, fromMs / 1000, toMs / 1000));
            } catch (LeapApi.SessionExpiredException e) {
                throw e;
            } catch (IOException e) {
                if (first == null) first = e;
            }
            CloudUsage.Window w = new CloudUsage.Window(fromMs, km, split, System.currentTimeMillis());
            if (!w.useful() && first != null) throw first;
            return w;
        } catch (LeapApi.SessionExpiredException e) {
            throw closed(st, e);
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    /** Consumo medio de las últimas semanas según el coche, o null si la nube no da cifras. */
    static synchronized CloudHistory.Weekly readWeekly(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        try {
            return CloudHistory.parseWeekly(api.weeklyConsumption(saved.vin));
        } catch (LeapApi.SessionExpiredException e) {
            throw closed(st, e);
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    /**
     * Entra con el correo y la contraseña (la contraseña no se guarda) y lista los coches. Si solo hay uno, queda
     * elegido; con varios, hay que elegirlo (chooseVehicle).
     */
    static synchronized List<LeapApi.Vehicle> login(Context ctx, String email, String password)
            throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        String deviceId = null;
        try {
            CarCloudStore.Saved old = st.session();
            if (old != null && old.session != null) deviceId = old.session.deviceId;
        } catch (IOException | GeneralSecurityException ignored) {
            // Sesión vieja ilegible: se empieza de cero.
        }
        // Tras «Cerrar sesión» ya no hay sesión: el deviceId de la anterior, para no estrenar móvil en cada entrada.
        if (deviceId == null) deviceId = st.lastDeviceId();
        api = null;
        saved = null;
        refreshFailedAtMs = 0;
        LeapApi a = newApi(st, deviceId);
        a.login(email, password);
        List<LeapApi.Vehicle> cars = a.vehicles();
        CarCloudStore.Saved s = new CarCloudStore.Saved();
        s.session = a.session();
        s.email = email.trim();
        if (cars.size() == 1) {
            s.vin = cars.get(0).vin;
            s.carType = cars.get(0).carType;
        }
        api = a;
        saved = s;
        persist(st);
        CarOrders.sessionRestored(ctx);
        L.i("nube Leapmotor: sesión iniciada (" + CarCloudStore.maskEmail(email) + "), " + cars.size() + " coche(s)"
                + (cars.size() == 1 ? ", modelo " + cars.get(0).carType : ""));
        CarCloud.settingsChanged();
        return cars;
    }

    /** Coches de la cuenta (para elegir otro). */
    static synchronized List<LeapApi.Vehicle> vehicles(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        int v0 = api.sessionVersion();
        try {
            return api.vehicles();
        } catch (LeapApi.SessionExpiredException e) {
            throw closed(st, e);
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    static synchronized void chooseVehicle(Context ctx, LeapApi.Vehicle v) throws IOException, GeneralSecurityException {
        CarCloudStore st = store(ctx);
        ensure(st);
        saved.vin = v.vin;
        saved.carType = v.carType;
        persist(st);
        L.i("nube Leapmotor: coche elegido, modelo " + v.carType);
        CarCloud.settingsChanged();
    }

    /** El VIN elegido termina en… (para enseñarlo: «…1234»), o "". Lee lo guardado: no espera al candado. */
    static String vinTail(Context ctx) {
        try {
            CarCloudStore.Saved s = new CarCloudStore(ctx).session();
            if (s == null || s.vin.length() < 4) return "";
            return "…" + s.vin.substring(s.vin.length() - 4);
        } catch (IOException | GeneralSecurityException e) {
            return "";
        }
    }

    /** El correo de la sesión (para rellenar el campo al volver a entrar), o "". Lee lo guardado: no espera al candado. */
    static String email(Context ctx) {
        try {
            CarCloudStore.Saved s = new CarCloudStore(ctx).session();
            return s == null ? "" : s.email;
        } catch (IOException | GeneralSecurityException e) {
            return "";
        }
    }

    /** Cierra la sesión: fuera tokens, coche y correo. El certificado se queda. */
    static synchronized void logout(Context ctx) {
        store(ctx).deleteSession();
        api = null;
        saved = null;
        disarm();
        L.i("nube Leapmotor: sesión cerrada (por el usuario)");
        CarCloud.settingsChanged();
    }

    /** «Cerrar sesión y borrar datos»: todo, también el certificado y la clave del Keystore. */
    static synchronized void wipe(Context ctx) {
        store(ctx).wipeAll();
        api = null;
        saved = null;
        disarm();
        L.i("nube Leapmotor: sesión cerrada y datos borrados (certificado, sesión y ajustes)");
        CarCloud.settingsChanged();
    }
}
