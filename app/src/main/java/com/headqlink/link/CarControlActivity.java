package com.headqlink.link;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * La ventanita del widget «Coche» sobre la pantalla de inicio: escucha (voz de Android, sin micrófono propio), entiende
 * la frase (CarCommand.parse), confirma lo que abre el coche y manda la orden (CarCloudSession.command), con su
 * resultado. Transparente, en su propia tarea y fuera de recientes.
 */
public final class CarControlActivity extends AppCompatActivity {
    static final String EXTRA_WHAT = "what";
    static final String VOICE = "voice";
    private static final int REQ_SPEECH = 1;
    /** Con el resultado bueno a la vista, la ventanita se cierra sola tras esto. */
    private static final long CLOSE_AFTER_MS = 2500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        if (b != null) return;
        if (!CarControlWidget.enabled(this)) {
            show(new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.hql_cc_disabled_title)
                    .setMessage(R.string.hql_cc_disabled_text)
                    .setPositiveButton(R.string.hql_cc_open_settings, (d, w) -> {
                        startActivity(new Intent(this, CarCloudActivity.class));
                        finish();
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> finish()));
            return;
        }
        String what = getIntent().getStringExtra(EXTRA_WHAT);
        if (VOICE.equals(what)) {
            listen();
            return;
        }
        CarCommand c = CarCommand.fromButton(what);
        if (c == null) {
            finish();
            return;
        }
        confirmAndSend(c);
    }

    /** La voz de Android (Google), en el idioma de la app; el resultado llega a onActivityResult. */
    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Str.get(R.string.hql_cc_voice_prompt));
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            L.w("widget «Coche»: no hay reconocimiento de voz en el móvil");
            message(Str.get(R.string.hql_cc_no_voice), false);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SPEECH) return;
        ArrayList<String> heard = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (res != Activity.RESULT_OK || heard == null || heard.isEmpty()) {
            finish();
            return;
        }
        for (String h : heard) {
            CarCommand c = CarCommand.parse(h);
            if (c != null) {
                L.i("widget «Coche»: por voz → " + c);
                confirmAndSend(c);
                return;
            }
        }
        L.i("widget «Coche»: por voz, sin entender (" + heard.size() + " opciones)");
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(Str.get(R.string.hql_cc_not_understood, heard.get(0)))
                .setMessage(R.string.hql_cc_voice_prompt)
                .setPositiveButton(R.string.hql_cc_retry, (d, w) -> listen())
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish()));
    }

    /** Lo que abre el coche, con un toque de confirmación; lo demás, directo. */
    private void confirmAndSend(CarCommand c) {
        if (!c.needsConfirm()) {
            send(c);
            return;
        }
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(c.kind == CarCommand.Kind.UNLOCK ? R.string.hql_cc_confirm_unlock : R.string.hql_cc_confirm_trunk)
                .setPositiveButton(R.string.hql_cc_confirm_yes, (d, w) -> send(c))
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish()));
    }

    private void send(CarCommand c) {
        message(Str.get(R.string.hql_cc_sending, c.label()), true);
        new Thread(() -> {
            String result;
            boolean good;
            try {
                good = CarCloudSession.command(this, c);
                result = Str.get(good ? R.string.hql_cc_done : R.string.hql_cc_sent, c.label());
            } catch (Exception e) {
                good = false;
                L.w("widget «Coche»: orden " + c + " fallida: " + Http.safeError(e));
                result = Str.get(R.string.hql_cc_failed, c.label(), Http.safeError(e));
            }
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            new Config(this).putString(CarControlWidget.K_LAST, result + " · " + time);
            CarControlWidget.refresh(this);
            String r = result;
            boolean ok = good;
            main.post(() -> {
                if (isFinishing()) return;
                message(r, false);
                if (ok) main.postDelayed(this::finish, CLOSE_AFTER_MS);
            });
        }, "car-command").start();
    }

    /** Un texto en la ventanita: mientras se manda, sin botones; al acabar, con «Aceptar». */
    private void message(String text, boolean working) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this).setMessage(text);
        if (!working) b.setPositiveButton(android.R.string.ok, (d, w) -> finish());
        show(b);
        dialog.setCancelable(!working);
    }

    private void show(MaterialAlertDialogBuilder b) {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        dialog = b.create();
        dialog.setOnDismissListener((DialogInterface d) -> {
            if (d == dialog && !isChangingConfigurations()) finish();
        });
        dialog.show();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        super.onDestroy();
    }
}
