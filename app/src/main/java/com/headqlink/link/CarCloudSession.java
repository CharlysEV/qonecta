package com.headqlink.link;

import android.content.Context;

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

    private CarCloudSession() {
    }

    /** Solo pruebas: otro transporte (y se olvida el cliente actual). */
    static synchronized void setTransportForTest(LeapApi.Transport t) {
        testTransport = t;
        api = null;
        saved = null;
        stale = false;
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
            // La clave del Keystore ya no abre lo guardado (se borró, o el móvil se restauró): hay que volver a configurarlo.
            throw new NotConfiguredException("certificado guardado ilegible (" + e.getClass().getSimpleName() + ")");
        }
        if (id == null) throw new NotConfiguredException("sin certificado de cliente");
        return new LeapApi(transport(st), id, System::currentTimeMillis, new SecureRandom(), deviceId);
    }

    /** Cliente con la sesión guardada, o NotConfigured si falta algo. */
    private static void ensure(CarCloudStore st) throws IOException, GeneralSecurityException {
        if (stale) {
            stale = false;
            api = null;
            saved = null;
        }
        if (api != null && api.loggedIn() && saved != null) return;
        CarCloudStore.Saved s;
        try {
            s = st.session();
        } catch (GeneralSecurityException e) {
            throw new NotConfiguredException("sesión guardada ilegible (" + e.getClass().getSimpleName() + ")");
        }
        if (s == null || s.session == null || !s.session.usable()) throw new NotConfiguredException("sin sesión");
        LeapApi a = newApi(st, s.session.deviceId);
        a.restore(s.session);
        api = a;
        saved = s;
    }

    private static void persist(CarCloudStore st) throws IOException, GeneralSecurityException {
        if (api == null || saved == null) return;
        saved.session = api.session();
        st.saveSession(saved);
        if (saved.session == null) return;
        // Se vuelve a entrar como el mismo móvil aunque se cierre la sesión (como la app oficial).
        st.rememberDeviceId(saved.session.deviceId);
        // Para el log, una vez por token: cuándo caduca (para saber si la nube lo rechaza por caducado o antes).
        String tok = saved.session.token;
        if (tok != null && !tok.equals(loggedToken)) {
            loggedToken = tok;
            long exp = LeapCrypto.tokenExpiryMs(tok);
            if (exp > 0) {
                L.i(String.format(java.util.Locale.US, "nube Leapmotor: token en uso, caduca en %.1f h",
                        (exp - System.currentTimeMillis()) / 3_600_000.0));
            }
        }
    }

    /** El último token del que se apuntó la caducidad. */
    private static String loggedToken;

    /** Se renueva el token cuando le queda menos de esto (antes, solo cuando la nube ya lo rechazaba, a veces horas tarde). */
    static final long REFRESH_AHEAD_MS = 60 * 60_000;

    /** Puro: ¿toca renovar ya? expMs: cuándo caduca el token (ms; 0 si no se sabe: no se toca). */
    static boolean refreshDue(long expMs, long nowMs) {
        return expMs > 0 && expMs - nowMs <= REFRESH_AHEAD_MS;
    }

    /** Con el cliente cargado: renueva el token si caduca pronto. true si lo renovó. */
    private static boolean refreshIfDue(CarCloudStore st) throws IOException, GeneralSecurityException {
        LeapApi.Session s = api.session();
        if (s == null || !refreshDue(LeapCrypto.tokenExpiryMs(s.token), System.currentTimeMillis())) return false;
        try {
            api.refresh();
        } catch (LeapApi.SessionExpiredException e) {
            // La nube ya no lo renueva: lo dirá la llamada siguiente (y el log lleva lo que contestó, LeapApi.refresh).
            L.w("nube Leapmotor: no se pudo renovar antes de caducar: " + e.getMessage());
            return false;
        }
        persist(st);
        L.i("nube Leapmotor: token renovado antes de caducar");
        return true;
    }

    /**
     * Mantiene la sesión viva sin abrir la app: la comprobación de fondo (ChargeCheckJob, ~15 min) la llama y el token se
     * renueva antes de caducar, para no tener que volver a poner la contraseña tras unas horas sin leer el coche.
     */
    static synchronized void keepAlive(Context ctx) {
        try {
            CarCloudStore st = new CarCloudStore(ctx);
            if (!st.hasSession()) return;
            ensure(st);
            refreshIfDue(st);
        } catch (NotConfiguredException ignored) {
            // Sin cuenta: nada que mantener.
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            L.w("nube Leapmotor: mantener la sesión: " + Http.safeError(e));
        }
    }

    /** Estado del coche elegido (lo usan el sondeo y «Leer estado ahora»). */
    static synchronized LeapStatus readStatus(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        refreshIfDue(st);
        int v0 = api.sessionVersion();
        JSONObject data;
        try {
            data = api.status(saved.vin, saved.carType);
        } catch (LeapApi.SessionExpiredException e) {
            api = null;
            throw e;
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
        CarCloudStore st = new CarCloudStore(ctx);
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
            api = null;
            throw e;
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
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        String pin = st.pin();
        if (pin == null || pin.isEmpty()) throw new NotConfiguredException("sin PIN del coche");
        CarCloud.Snapshot snap = CarCloud.snapshot();
        double inside = snap != null && snap.hasData() && !snap.demo ? snap.status.interiorTempC : Double.NaN;
        int v0 = api.sessionVersion();
        try {
            boolean ok = api.remote(saved.vin, c.cmdId(), c.content(saved.carType, inside), pin);
            L.i("nube Leapmotor: orden " + c + (ok ? " (confirmada por el coche)" : " (sin confirmar)"));
            return ok;
        } catch (LeapApi.SessionExpiredException e) {
            api = null;
            throw e;
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
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        try {
            JSONObject data = api.status(saved.vin, saved.carType);
            LeapStatus.Position p = LeapStatus.position(data);
            L.i("nube Leapmotor: «Localizar»: " + (p == null ? "el coche no da su posición" : "posición leída (no se guarda)"));
            return p == null ? null : new Found(p, LeapStatus.dataTime(LeapStatus.merge(data)));
        } catch (LeapApi.SessionExpiredException e) {
            api = null;
            throw e;
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    /** Consumo medio de las últimas semanas según el coche, o null si la nube no da cifras. */
    static synchronized CloudHistory.Weekly readWeekly(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        if (saved.vin.isEmpty()) throw new NotConfiguredException("sin coche elegido");
        int v0 = api.sessionVersion();
        try {
            return CloudHistory.parseWeekly(api.weeklyConsumption(saved.vin));
        } catch (LeapApi.SessionExpiredException e) {
            api = null;
            throw e;
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
        CarCloudStore st = new CarCloudStore(ctx);
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
        L.i("nube Leapmotor: sesión iniciada (" + CarCloudStore.maskEmail(email) + "), " + cars.size() + " coche(s)"
                + (cars.size() == 1 ? ", modelo " + cars.get(0).carType : ""));
        CarCloud.settingsChanged();
        return cars;
    }

    /** Coches de la cuenta (para elegir otro). */
    static synchronized List<LeapApi.Vehicle> vehicles(Context ctx) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        ensure(st);
        int v0 = api.sessionVersion();
        try {
            return api.vehicles();
        } finally {
            if (api != null && api.sessionVersion() != v0) persist(st);
        }
    }

    static synchronized void chooseVehicle(Context ctx, LeapApi.Vehicle v) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
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
        new CarCloudStore(ctx).deleteSession();
        api = null;
        saved = null;
        L.i("nube Leapmotor: sesión cerrada");
        CarCloud.settingsChanged();
    }

    /** «Cerrar sesión y borrar datos»: todo, también el certificado y la clave del Keystore. */
    static synchronized void wipe(Context ctx) {
        new CarCloudStore(ctx).wipeAll();
        api = null;
        saved = null;
        L.i("nube Leapmotor: sesión cerrada y datos borrados (certificado, sesión y ajustes)");
        CarCloud.settingsChanged();
    }
}
