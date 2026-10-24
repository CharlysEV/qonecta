package com.headqlink.link;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

/**
 * Avisos por voz (el plan de carga cambia durante el viaje), con la voz del sistema en el idioma de la app y como los
 * avisos del navegador: uso «guía de navegación» y la música baja un momento mientras habla (sale por donde sale el
 * audio del móvil: el Bluetooth del coche).
 */
final class VoiceAlert {
    private static TextToSpeech tts;
    private static boolean ready;
    private static String pending;
    private static AudioFocusRequest focus;
    private static int seq;
    /** Quién mandó lo que se dice o se va a decir («» los avisos del plan; RoadGuide.VOICE, la guía). */
    private static String owner = "";
    /** Lo que se está diciendo (id), desde speak hasta que acaba, falla o se corta; null si nada. */
    private static String activeId;
    private static long activeSince;
    /** Si el motor de voz no avisara del final, a este tiempo ya no cuenta como hablando. */
    private static final long ACTIVE_MAX_MS = 90_000;
    /** Al acabar o cortarse lo que se decía (la guía quita su «Parar»). Cualquier hilo. */
    static volatile Runnable onIdle;

    private VoiceAlert() {
    }

    /** Dice el texto (el anterior, si sigue hablando, se corta). Se puede llamar desde cualquier hilo. */
    static synchronized void say(Context ctx, String text) {
        say(ctx, text, "");
    }

    /** Como say, con quién lo manda (para cortarlo luego con stop). */
    static synchronized void say(Context ctx, String text, String who) {
        if (text == null || text.trim().isEmpty()) return;
        owner = who == null ? "" : who;
        Context app = ctx.getApplicationContext();
        if (tts == null) {
            pending = text;
            ready = false;
            tts = new TextToSpeech(app, status -> onInit(app, status));
            return;
        }
        if (!ready) {
            pending = text;
            return;
        }
        speak(app, text);
    }

    /** ¿Está hablando ahora? (la guía del camino no corta un aviso del plan) */
    static synchronized boolean speaking() {
        return pending != null
                || (activeId != null && android.os.SystemClock.elapsedRealtime() - activeSince < ACTIVE_MAX_MS);
    }

    /** ¿Está hablando ahora, y es de who? */
    static synchronized boolean speaking(String who) {
        return owner.equals(who) && speaking();
    }

    /** Corta lo que se esté diciendo si es de who («Parar» de la guía); lo de otros sigue. */
    static synchronized void stop(Context ctx, String who) {
        if (!owner.equals(who) || !speaking()) return;
        pending = null;
        activeId = null;
        if (tts != null && ready) tts.stop();
        release(ctx.getApplicationContext());
        idle();
    }

    /** Lo que se decía (id) ha terminado: sin «hablando», la música vuelve (releaseFocus) y se avisa. */
    private static synchronized void finished(Context app, String id, boolean releaseFocus) {
        // Un aviso cortado por otro nuevo (QUEUE_FLUSH) avisa tarde: el nuevo sigue hablando.
        if (id == null || !id.equals(activeId)) return;
        activeId = null;
        if (releaseFocus) release(app);
        idle();
    }

    private static void idle() {
        Runnable r = onIdle;
        if (r != null) r.run();
    }

    private static synchronized void onInit(Context app, int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            L.w("voz: el sistema no tiene voz (" + status + ")");
            shutdown();
            return;
        }
        int r = tts.setLanguage(Str.locale());
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            L.w("voz: sin voz para " + Str.locale() + "; con la del sistema");
        }
        if (Build.VERSION.SDK_INT >= 21) {
            tts.setAudioAttributes(attributes());
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                finished(app, id, true);
            }

            @Override
            public void onError(String id) {
                finished(app, id, true);
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                finished(app, id, true);
            }
        });
        ready = true;
        String p = pending;
        pending = null;
        if (p != null) speak(app, p);
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    private static void speak(Context app, String text) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focus == null) {
                    focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes())
                            .build();
                }
                am.requestAudioFocus(focus);
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        }
        String id = "hql-" + (++seq);
        activeId = id;
        activeSince = android.os.SystemClock.elapsedRealtime();
        if (Build.VERSION.SDK_INT >= 21) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id);
        } else {
            java.util.HashMap<String, String> p = new java.util.HashMap<>();
            p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, p);
        }
        L.i("voz: aviso dicho (" + text.length() + " caracteres)");
    }

    /** Terminó de hablar: la música vuelve a su volumen. */
    private static synchronized void release(Context app) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focus != null) am.abandonAudioFocusRequest(focus);
        } else {
            am.abandonAudioFocus(null);
        }
    }

    /** Al cerrar el modo ampliado. */
    static synchronized void shutdown() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
            }
        }
        tts = null;
        ready = false;
        pending = null;
        activeId = null;
    }
}
