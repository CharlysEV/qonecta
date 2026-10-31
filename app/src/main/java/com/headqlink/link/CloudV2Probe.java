package com.headqlink.link;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Variante de pruebas: prueba del protocolo nuevo de la nube de Leapmotor, el de la app oficial V1.16.4-1 que usa
 * Leapmotor Mate. Desde el 28-10 el historial de viajes rechaza el protocolo antiguo (código -1); esto comprueba, con la
 * contraseña que escribe el usuario, si el nuevo lo lee: entra, pide la ruta del coche y una página del historial, y
 * mira si la sesión antigua sigue viva. No guarda nada (ni la sesión nueva ni la contraseña), y el log solo lleva
 * nombres de campos, códigos y cuentas: nunca tokens, claves, el VIN ni el correo. Contratos de leapmotor-mate
 * (ProtossBlaster, AGPL-3.0).
 */
final class CloudV2Probe {
    /** Días de historial que se piden. */
    static final int DAYS = 7;

    private CloudV2Probe() {
    }

    /** Hace la prueba y devuelve el resumen para la pantalla. Bloquea: solo desde otro hilo. */
    static String run(Context ctx, String email, String password) throws IOException, GeneralSecurityException {
        CarCloudStore st = new CarCloudStore(ctx);
        LeapTls.Identity app = st.identity();
        if (app == null) return "Falta el certificado de cliente.";
        CarCloudStore.Saved saved = null;
        try {
            saved = st.session();
        } catch (IOException | GeneralSecurityException e) {
            // Sin sesión guardada: la prueba entra igual, pero no sabe qué coche leer.
        }
        String vin = saved == null ? "" : saved.vin;
        String deviceId = saved != null && saved.session != null ? saved.session.deviceId : st.lastDeviceId();
        if (deviceId == null || deviceId.isEmpty()) deviceId = LeapCrypto.newDeviceId(new SecureRandom());
        LeapHttps http = new LeapHttps(new LeapTls.PinTrust(st.acceptedPins()));
        SecureRandom rnd = new SecureRandom();
        List<String> out = new ArrayList<>();

        // 1. Login nuevo.
        Map<String, String> body = new LinkedHashMap<>();
        body.put("identifier", email.trim());
        body.put("identifierType", "2");
        body.put("security", password);
        Map<String, String> core = core(deviceId, rnd);
        Map<String, String> signed = new LinkedHashMap<>(core);
        signed.putAll(body);
        Map<String, String> h = new LinkedHashMap<>(core);
        h.put("sign", LeapCrypto.loginSignV2(signed));
        h.put("carvin", "");
        h.put("cartype", "");
        extra(h);
        JSONObject data = data(http.sendV2(app, "POST", LeapApi.V2_GLOBAL_ORIGIN, LeapApi.V2_PATH_LOGIN, h,
                new JSONObject(body).toString()), "login nuevo", out);
        if (data == null) return finish(out);
        JSONObject sp = data.optJSONObject("signParam");
        String token = data.optString("accessToken", data.optString("token"));
        line(out, "login nuevo: OK · campos " + keys(data) + (sp == null ? " · sin signParam" : " · signParam " + keys(sp)));
        line(out, "login nuevo: trae también la firma antigua (signIkm): " + (data.has("signIkm") ? "sí" : "no"));
        byte[] key = sp == null ? null : LeapCrypto.signKeyV2(token, sp.optString("r2"), sp.optString("r3"));
        if (key == null) {
            line(out, "login nuevo: no sale la clave de firma (token o r2/r3)");
            return finish(out);
        }
        String accountId = data.optString("accountId", data.optString("id"));
        LeapTls.Identity acct;
        try {
            byte[] p12 = android.util.Base64.decode(data.optString("base64Cert"), android.util.Base64.DEFAULT);
            acct = LeapTls.loadPkcs12(p12, LeapCrypto.accountP12Password(accountId, data.optString("uid")).toCharArray());
        } catch (IllegalArgumentException | IOException | GeneralSecurityException e) {
            line(out, "login nuevo: el certificado de cuenta no se abre (" + e.getClass().getSimpleName() + ")");
            return finish(out);
        }
        String dev = LeapCrypto.sessionDeviceId(token, deviceId);
        if (vin.isEmpty()) {
            line(out, "sin coche elegido en la sesión guardada: no puedo pedir el historial");
            return finish(out);
        }

        // 2. Ruta del coche (su servidor).
        Map<String, String> q = new LinkedHashMap<>();
        q.put("vin", vin);
        JSONObject route = data(http.sendV2(acct, "GET", LeapApi.V2_GLOBAL_ORIGIN,
                LeapApi.V2_PATH_ROUTE + "?vin=" + LeapCrypto.encodeComponent(vin), authHeaders(key, dev, token, accountId, vin, q, rnd),
                ""), "ruta del coche", out);
        String center = route == null ? "" : route.optString("appCenter");
        if (!LeapHttps.v2Origin(center)) {
            line(out, "ruta del coche: sin servidor válido; pruebo con el de siempre");
            center = LeapApi.BASE_URL;
        } else {
            line(out, "ruta del coche: OK · servidor " + center.replace("https://", ""));
        }

        // 3. Una página del historial con el protocolo nuevo.
        long toS = System.currentTimeMillis() / 1000;
        long fromS = toS - DAYS * 86_400L;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("vin", vin);
        p.put("startTime", String.valueOf(fromS));
        p.put("endTime", String.valueOf(toS));
        p.put("pageNum", "1");
        p.put("pageSize", "20");
        JSONObject json = new JSONObject();
        try {
            json.put("vin", vin);
            json.put("startTime", String.valueOf(fromS));
            json.put("endTime", String.valueOf(toS));
            json.put("pageNum", 1);
            json.put("pageSize", 20);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        JSONObject page = data(http.sendV2(acct, "POST", center, LeapApi.PATH_TRIPS, authHeaders(key, dev, token, accountId, vin, p, rnd),
                json.toString()), "historial nuevo", out);
        if (page != null) {
            line(out, "historial nuevo: OK · " + CloudHistory.parsePage(page).size() + " viajes en " + DAYS + " días");
        }

        // 4. ¿Sigue viva la sesión antigua?
        try {
            CarCloudSession.readStatus(ctx);
            line(out, "sesión antigua: sigue funcionando");
        } catch (IOException | GeneralSecurityException e) {
            line(out, "sesión antigua: ya no vale (" + e.getClass().getSimpleName() + "); vuelve a entrar con «Entrar»");
        }
        return finish(out);
    }

    /** Las cabeceras firmadas del protocolo nuevo (sin «sign»). */
    private static Map<String, String> core(String deviceId, SecureRandom rnd) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("source", LeapCrypto.SOURCE);
        c.put("channel", LeapCrypto.CHANNEL);
        c.put("acceptLanguage", LeapCrypto.LANGUAGE_V2);
        c.put("version", LeapCrypto.APP_VERSION_V2);
        c.put("deviceType", LeapCrypto.DEVICE_TYPE_V2);
        c.put("nonce", LeapCrypto.nonce(rnd));
        c.put("timestamp", String.valueOf(System.currentTimeMillis()));
        c.put("deviceId", deviceId);
        return c;
    }

    /** Las cabeceras fijas del protocolo nuevo. */
    private static void extra(Map<String, String> h) {
        h.put("Content-Type", "application/json");
        h.put("x-region", "EU");
        h.put("x-api-signature-version", "2.0");
        h.put("X-P12_ENC_ALG", LeapCrypto.P12_ENC_ALG);
    }

    /** Cabeceras de una petición con sesión: HMAC con la clave nueva de las cabeceras firmadas y los parámetros. */
    private static Map<String, String> authHeaders(byte[] key, String deviceId, String token, String userId, String vin,
                                                   Map<String, String> params, SecureRandom rnd) {
        Map<String, String> core = core(deviceId, rnd);
        Map<String, String> signed = new LinkedHashMap<>(core);
        signed.putAll(params);
        Map<String, String> h = new LinkedHashMap<>(core);
        h.put("sign", LeapCrypto.sign(key, signed));
        h.put("token", token);
        h.put("userId", userId);
        h.put("carvin", vin);
        extra(h);
        return h;
    }

    /** El «data» de una respuesta correcta, o null (y la línea de por qué). */
    private static JSONObject data(LeapApi.Response r, String step, List<String> out) {
        if (r.code != 200) {
            line(out, step + ": HTTP " + r.code);
            return null;
        }
        JSONObject e;
        try {
            e = new JSONObject(r.body);
        } catch (JSONException x) {
            line(out, step + ": la respuesta no es JSON");
            return null;
        }
        String code = e.has("code") ? e.optString("code") : e.optString("result", "?");
        if (!"0".equals(code)) {
            String msg = e.optString("message", e.optString("msg"));
            line(out, step + ": código " + code + (msg.isEmpty() || msg.length() > 80 ? "" : " (" + msg + ")"));
            return null;
        }
        JSONObject d = e.optJSONObject("data");
        if (d == null) line(out, step + ": sin datos");
        return d;
    }

    /** Los nombres de los campos, ordenados (nunca sus valores). */
    static String keys(JSONObject o) {
        List<String> k = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) k.add(it.next());
        Collections.sort(k);
        return k.toString();
    }

    private static void line(List<String> out, String s) {
        out.add(s);
        L.i("nube Leapmotor (prueba del protocolo nuevo): " + s);
    }

    private static String finish(List<String> out) {
        return String.join("\n", out);
    }
}
