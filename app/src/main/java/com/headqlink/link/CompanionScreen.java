package com.headqlink.link;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

/**
 * El compañero de voz en el coche (ClaudeCompanion): un botón grande para hablar; lo que se entiende se manda a Claude y
 * la respuesta se dice en voz alta (y se ve debajo). Tocar mientras habla lo calla. «Nueva conversación» empieza de cero.
 * Sin clave (o en un Android antiguo), dice cómo configurarlo en el móvil.
 */
final class CompanionScreen implements CarScreen {
    static final String VOICE = "companion";
    /** Solo las capturas del PC: la pantalla como con la clave puesta (sin Keystore). */
    static boolean preview;

    private Host host;
    private TextView status;
    private ImageView mic;
    private TextView you;
    private TextView answer;
    private android.speech.SpeechRecognizer speech;
    private boolean busy;
    /** Se está diciendo la respuesta: al acabar, vuelve «Toca para hablar». */
    private boolean talking;
    private boolean destroyed;
    private final Runnable watchVoice = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (talking && !VoiceAlert.speaking(VOICE)) {
                talking = false;
                setStatus(R.string.hql_companion_tap);
            }
            mic.postDelayed(this, 500);
        }
    };

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        if (!ClaudeCompanion.supported()) return CarStyle.message(c, Str.get(R.string.hql_companion_old_android));
        if (!preview && !ClaudeCompanion.hasKey(c)) return CarStyle.message(c, Str.get(R.string.hql_companion_no_key));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(28, 20, 28, 16);
        col.setBackgroundColor(CarKit.BG);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = CarStyle.text(c, Str.get(R.string.hql_companion_title), 32, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(title);
        TextView model = CarStyle.text(c, ClaudeCompanion.fast(c) ? "Claude Haiku 5.5" : "Claude Opus 5.5", 20, CarStyle.TEXT_DIM);
        titles.addView(model);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView fresh = CarStyle.pill(c, Str.get(R.string.hql_companion_new));
        fresh.setOnClickListener(v -> {
            ClaudeCompanion.reset();
            VoiceAlert.stop(c, VOICE);
            you.setText("");
            answer.setText("");
            setStatus(R.string.hql_companion_tap);
        });
        head.addView(fresh);
        col.addView(head);

        // El botón de hablar, grande y en el centro.
        LinearLayout center = new LinearLayout(c);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setPadding(0, 24, 0, 12);
        mic = new ImageView(c);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setImageTintList(ColorStateList.valueOf(CarStyle.ON_ACCENT));
        mic.setBackground(CarStyle.round(CarStyle.ACCENT, 90));
        mic.setPadding(44, 44, 44, 44);
        mic.setContentDescription(Str.get(R.string.hql_companion_tap));
        mic.setOnClickListener(v -> tap());
        center.addView(mic, new LinearLayout.LayoutParams(180, 180));
        status = CarStyle.text(c, Str.get(R.string.hql_companion_tap), 26, CarStyle.TEXT_DIM);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 16, 0, 0);
        center.addView(status);
        col.addView(center);

        // Lo último: lo dicho y la respuesta.
        LinearLayout talk = new LinearLayout(c);
        talk.setOrientation(LinearLayout.VERTICAL);
        you = CarStyle.text(c, "", 24, CarStyle.TEXT_DIM);
        answer = CarStyle.text(c, "", 30, CarStyle.TEXT);
        answer.setPadding(0, 10, 0, 0);
        talk.addView(you);
        talk.addView(answer);
        ScrollView sv = new ScrollView(c);
        sv.addView(talk);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView note = CarStyle.text(c, Str.get(R.string.hql_companion_note), 17, CarStyle.TEXT_DIM);
        note.setPadding(4, 8, 0, 0);
        col.addView(note);
        mic.postDelayed(watchVoice, 500);
        return col;
    }

    private void setStatus(int res) {
        status.setText(Str.get(res));
    }

    /** Tocar: si habla, se calla; si escucha, para; si no, escucha. */
    private void tap() {
        Context c = host.context();
        if (VoiceAlert.speaking(VOICE)) {
            VoiceAlert.stop(c, VOICE);
            talking = false;
            setStatus(R.string.hql_companion_tap);
            return;
        }
        if (busy) return;
        if (speech != null) {
            stopListening();
            setStatus(R.string.hql_companion_tap);
            return;
        }
        listen(c);
    }

    private void listen(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status.setText(Str.get(R.string.hql_search_voice_permission));
            return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(c)) {
            status.setText(Str.get(R.string.hql_search_voice_none));
            return;
        }
        speech = android.speech.SpeechRecognizer.createSpeechRecognizer(c);
        speech.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override
            public void onReadyForSpeech(android.os.Bundle params) {
                setStatus(R.string.hql_companion_listening);
                mic.setBackground(CarStyle.round(CarStyle.BAD, 90));
            }

            @Override
            public void onPartialResults(android.os.Bundle b) {
                String t = first(b);
                if (t != null && !t.isEmpty()) you.setText(Str.get(R.string.hql_companion_you, t));
            }

            @Override
            public void onResults(android.os.Bundle b) {
                String t = first(b);
                stopListening();
                if (t == null || t.trim().isEmpty()) {
                    setStatus(R.string.hql_companion_nothing);
                    return;
                }
                you.setText(Str.get(R.string.hql_companion_you, t.trim()));
                send(c, t.trim());
            }

            @Override
            public void onError(int error) {
                stopListening();
                L.i("compañero: la voz no entendió nada (error " + error + ")");
                status.setText(Str.get(error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                        ? R.string.hql_search_voice_permission : R.string.hql_companion_nothing));
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                setStatus(R.string.hql_companion_thinking);
            }

            @Override
            public void onEvent(int eventType, android.os.Bundle params) {
            }
        });
        Intent in = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        in.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        in.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Str.locale().toLanguageTag());
        in.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        in.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            speech.startListening(in);
        } catch (RuntimeException e) {
            stopListening();
            status.setText(Str.get(R.string.hql_search_voice_none));
        }
    }

    /** A Claude, fuera del hilo principal; la respuesta, en voz alta y en la pantalla. */
    private void send(Context c, String said) {
        busy = true;
        setStatus(R.string.hql_companion_thinking);
        String ctx = ClaudeCompanion.context(c);
        new Thread(() -> {
            ClaudeCompanion.Reply r = ClaudeCompanion.ask(c, said, ctx);
            host.post(() -> {
                busy = false;
                if (destroyed) return;
                answer.setText(r.text);
                answer.setTextColor(r.outcome == ClaudeCompanion.Outcome.OK ? CarStyle.TEXT : CarStyle.BAD);
                VoiceAlert.say(c, r.text, VOICE);
                talking = true;
                setStatus(R.string.hql_companion_speaking);
            });
        }, "hql-companion").start();
    }

    private static String first(android.os.Bundle b) {
        java.util.ArrayList<String> r = b == null ? null : b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private void stopListening() {
        if (mic != null) mic.setBackground(CarStyle.round(CarStyle.ACCENT, 90));
        if (speech == null) return;
        try {
            speech.cancel();
            speech.destroy();
        } catch (RuntimeException ignored) {
            // Ya parado.
        }
        speech = null;
    }

    @Override
    public void destroy() {
        destroyed = true;
        stopListening();
        if (host != null) VoiceAlert.stop(host.context(), VOICE);
    }

    /** Una conversación en marcha no se corta al pasar de día a noche. */
    @Override
    public boolean keepOnThemeChange() {
        return busy || speech != null || VoiceAlert.speaking(VOICE);
    }
}
