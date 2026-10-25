package com.headqlink.link;

import android.content.Context;
import android.os.Build;

import com.andrerinas.openheadunit.R;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * El compañero de voz: una conversación hablada con Claude (la API de Anthropic, con la clave del propio usuario), para
 * los viajes largos. Lo que se dice se manda con un poco de contexto del coche (hora, batería, autonomía, en marcha o
 * parado; nunca la posición) y la respuesta, corta y para decirla en voz alta, vuelve por la voz de la app.
 * - Modelo: Claude Opus 5.5 (o Claude Haiku 5.5, más rápido y barato, si se elige), con esfuerzo bajo: respuestas
 *   rápidas. Con Opus, las negativas de seguridad se reintentan en el modelo que Anthropic recomiende (fallbacks
 *   «default», beta server-side-fallback-2026-07-01).
 * - La conversación solo crece (el historial no se edita): al llegar a MAX_TURNS, empieza otra.
 * - La clave va cifrada con el Keystore de Android (como la cuenta de Leapmotor), fuera de las copias de seguridad.
 * - El SDK usa java.time: solo Android 8 o más (supported()).
 * El registro no lleva lo que se dice ni lo que contesta.
 */
final class ClaudeCompanion {
    static final String MODEL = "claude-opus-5-5";
    static final String MODEL_FAST = "claude-haiku-5-5";
    /** Preguntas por conversación; después, una nueva (así el historial nunca se recorta). */
    static final int MAX_TURNS = 12;
    static final long MAX_TOKENS = 2048;
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final String F_KEY = "companion-key.bin";
    private static final String ALIAS = "hql_companion_v1";
    private static final String PREFS = "hql_companion";
    private static final String K_FAST = "fast";

    /** Qué ha pasado con una pregunta. */
    enum Outcome { OK, NO_KEY, BAD_KEY, RATE_LIMIT, OVERLOADED, OFFLINE, REFUSED, FAILED }

    static final class Reply {
        final Outcome outcome;
        final String text;

        Reply(Outcome outcome, String text) {
            this.outcome = outcome;
            this.text = text;
        }
    }

    private static final List<BetaMessageParam> history = new ArrayList<>();
    private static int turns;
    private static AnthropicClient client;
    private static String clientKey;

    private ClaudeCompanion() {
    }

    /** El SDK necesita java.time (Android 8+). */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    // ------------------------------------------------------------------ la clave y el modelo

    private static File keyFile(Context c) {
        return new File(c.getNoBackupFilesDir(), F_KEY);
    }

    static boolean hasKey(Context c) {
        return supported() && keyFile(c).isFile();
    }

    /** Guarda la clave (cifrada); vacía, la borra. */
    static boolean saveKey(Context c, String key) {
        String k = key == null ? "" : key.trim();
        File f = keyFile(c);
        if (k.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            new CarCloudStore.KeystoreBox(ALIAS).destroy();
            reset();
            L.i("compañero: clave borrada");
            return true;
        }
        try {
            Files.write(f.toPath(), new CarCloudStore.KeystoreBox(ALIAS).seal(k.getBytes(StandardCharsets.UTF_8)));
            reset();
            L.i("compañero: clave guardada");
            return true;
        } catch (Exception e) {
            L.w("compañero: no se pudo guardar la clave (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    private static String key(Context c) {
        try {
            byte[] sealed = Files.readAllBytes(keyFile(c).toPath());
            return new String(new CarCloudStore.KeystoreBox(ALIAS).open(sealed), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean fast(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_FAST, false);
    }

    static void setFast(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_FAST, on).apply();
    }

    /** Puro: «sk-ant-…» con algo detrás. */
    static boolean looksLikeKey(String k) {
        return k != null && k.trim().startsWith("sk-ant-") && k.trim().length() > 20;
    }

    // ------------------------------------------------------------------ la conversación

    /** Empieza de cero (botón «Nueva conversación» o clave nueva). */
    static synchronized void reset() {
        history.clear();
        turns = 0;
    }

    static synchronized int turns() {
        return turns;
    }

    /** Pregunta (fuera del hilo principal): lo dicho, con el contexto del coche de ahora. */
    static synchronized Reply ask(Context c, String said, String context) {
        if (!supported()) return new Reply(Outcome.FAILED, Str.get(R.string.hql_companion_old_android));
        String key = key(c);
        if (key == null || key.isEmpty()) return new Reply(Outcome.NO_KEY, Str.get(R.string.hql_companion_no_key));
        if (turns >= MAX_TURNS) reset();
        if (client == null || !key.equals(clientKey)) {
            client = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(1).timeout(Duration.ofSeconds(45)).build();
            clientKey = key;
        }
        boolean fast = fast(c);
        String model = fast ? MODEL_FAST : MODEL;
        BetaMessageParam user = BetaMessageParam.ofUser(context.isEmpty() ? said : context + "\n\n" + said);
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build());
        // Con Opus, una negativa de seguridad se reintenta en el modelo que recomiende Anthropic (Haiku no tiene).
        if (!fast) b.addBeta(FALLBACK_BETA).fallbacksDefault();
        for (BetaMessageParam m : history) b.addMessage(m);
        b.addMessage(user);
        long t0 = System.currentTimeMillis();
        try {
            BetaMessage msg = client.beta().messages().create(b.build());
            boolean refused = msg.stopReason().map(r -> r.equals(BetaStopReason.REFUSAL)).orElse(false);
            if (refused) {
                L.i("compañero: Claude no responde a eso (negativa)");
                return new Reply(Outcome.REFUSED, Str.get(R.string.hql_companion_refused));
            }
            StringBuilder text = new StringBuilder();
            for (BetaContentBlock block : msg.content()) {
                block.text().ifPresent(t -> text.append(text.length() > 0 ? " " : "").append(t.text()));
            }
            String out = speakable(text.toString());
            // La conversación solo crece: la pregunta y la respuesta entera (con sus bloques de pensamiento).
            history.add(user);
            history.add(msg.toParam());
            turns++;
            L.i(String.format(Locale.US, "compañero: respuesta en %d ms (%s, %d caracteres, pregunta %d de %d)",
                    System.currentTimeMillis() - t0, fast ? "rápido" : "Opus", out.length(), turns, MAX_TURNS));
            return new Reply(out.isEmpty() ? Outcome.FAILED : Outcome.OK, out.isEmpty() ? Str.get(R.string.hql_companion_failed) : out);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            L.w("compañero: la clave no vale (" + e.getClass().getSimpleName() + ")");
            return new Reply(Outcome.BAD_KEY, Str.get(R.string.hql_companion_bad_key));
        } catch (RateLimitException e) {
            L.w("compañero: demasiadas peticiones");
            return new Reply(Outcome.RATE_LIMIT, Str.get(R.string.hql_companion_rate));
        } catch (InternalServerException e) {
            L.w("compañero: Claude saturado o con un fallo (" + e.statusCode() + ")");
            return new Reply(Outcome.OVERLOADED, Str.get(R.string.hql_companion_busy));
        } catch (AnthropicServiceException e) {
            L.w("compañero: error " + e.statusCode() + " " + e.errorType().map(Object::toString).orElse(""));
            return new Reply(e.statusCode() == 529 ? Outcome.OVERLOADED : Outcome.FAILED,
                    Str.get(e.statusCode() == 529 ? R.string.hql_companion_busy : R.string.hql_companion_failed));
        } catch (AnthropicIoException e) {
            L.w("compañero: sin conexión con Claude (" + e.getClass().getSimpleName() + ")");
            return new Reply(Outcome.OFFLINE, Str.get(R.string.hql_companion_offline));
        } catch (RuntimeException e) {
            L.w("compañero: fallo (" + e.getClass().getSimpleName() + ")");
            return new Reply(Outcome.FAILED, Str.get(R.string.hql_companion_failed));
        }
    }

    /**
     * Las instrucciones: en el idioma de la app, corto y para oírlo en el coche (sin listas, sin formato, sin enlaces),
     * con la seguridad del conductor por delante. Fijas (no llevan la hora: eso va con cada pregunta).
     */
    static String systemPrompt() {
        String lang = Str.locale().getDisplayLanguage(Locale.ENGLISH);
        return "You are the voice companion inside Qonecta+, an app that shows Android Auto and extra panels on the screen of a "
                + "Leapmotor electric car. The people in the car talk to you by voice (speech recognition, so expect small "
                + "transcription mistakes) and hear your answer through the car speakers.\n\n"
                + "Always answer in " + lang + ", the language of the app.\n"
                + "Answers are spoken aloud, so keep them short and natural: one to three sentences, plain conversational text. "
                + "No lists, headings, markdown, emojis, URLs or symbols that cannot be read aloud. If a full answer would be "
                + "long, give the essential part and offer to go on.\n"
                + "The driver must keep their attention on the road: never ask them to read, look at or touch a screen while "
                + "driving, and keep it brief when the context says the car is moving. You cannot control the car, the app, "
                + "the navigation or the phone; if asked to, say so and suggest how they can do it themselves when parked.\n"
                + "Each message may start with a line of car context (time, battery, range, moving or parked) that the app "
                + "adds; use it only when it helps, and never mention that it was added.\n"
                + "Be warm and good company on a long trip: conversation, curiosities, quizzes for passengers, stories for "
                + "children, trip and charging tips for an electric car.";
    }

    /** Puro: lo que se puede leer en voz alta (sin asteriscos, almohadillas ni viñetas de markdown, en una línea). */
    static String speakable(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?m)^\\s*[-*•#]+\\s*", "").replaceAll("[*_`#]+", "").replaceAll("\\s+", " ");
        return t.trim();
    }

    /** «(Contexto: 21:30; batería 54 %; autonomía 230 km; en marcha.)», con lo que se sepa. Sin la posición. */
    static String context(Context c) {
        StringBuilder b = new StringBuilder();
        b.append(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Str.locale()).format(new java.util.Date()));
        CarCloud.Snapshot cs = CarCloud.car();
        if (cs != null && cs.hasData()) {
            double soc = cs.status.socBest();
            if (!Double.isNaN(soc)) b.append("; battery ").append(Math.round(soc)).append(" %");
            double range = cs.status.evRangeKm();
            if (!Double.isNaN(range)) b.append("; range ").append(Math.round(range)).append(" km");
            if (cs.status.charging()) b.append("; charging");
        }
        if (CarSensors.isRunning()) b.append(CarSensors.movingWithin(60_000) ? "; moving" : "; parked");
        return "(Car context: " + b + ".)";
    }
}
