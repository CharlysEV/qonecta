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
 * La ventanita de las órdenes al coche del widget de Qonecta+ sobre la pantalla de inicio: escucha (voz de Android, sin micrófono propio), entiende
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
        if (!CarOrders.enabled(this)) {
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
        if (c.kind == CarCommand.Kind.FIND) locate();
        else if (c.kind == CarCommand.Kind.CLIMATE_ON) climateDialog();
        else if (c.kind == CarCommand.Kind.TRUNK_OPEN) trunkDialog();
        else confirmAndSend(c);
    }

    /**
     * El botón del maletero: abrir o cerrar. Antes solo abría (con el maletero abierto no hacía nada; la voz sí sabía
     * cerrarlo). Con el último dato de la nube, lo que toca va en el botón principal y se dice cómo estaba.
     */
    private void trunkDialog() {
        CarCloud.Snapshot s = CarCloud.car();
        Boolean open = s == null || s.status == null ? null : s.status.bootOpen;
        boolean closeFirst = Boolean.TRUE.equals(open);
        CarCommand primary = new CarCommand(closeFirst ? CarCommand.Kind.TRUNK_CLOSE : CarCommand.Kind.TRUNK_OPEN);
        CarCommand other = new CarCommand(closeFirst ? CarCommand.Kind.TRUNK_OPEN : CarCommand.Kind.TRUNK_CLOSE);
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this).setTitle(R.string.hql_cc_trunk_title);
        if (open != null) b.setMessage(open ? R.string.hql_cc_trunk_was_open : R.string.hql_cc_trunk_was_closed);
        b.setPositiveButton(primary.label(), (d, w) -> send(primary));
        b.setNeutralButton(other.label(), (d, w) -> send(other));
        b.setNegativeButton(android.R.string.cancel, (d, w) -> finish());
        show(b);
    }

    /** Config: la última temperatura y el modo del clima. */
    static final String K_CLIMATE_TEMP = "car_climate_temp";
    static final String K_CLIMATE_MODE = "car_climate_mode";

    private int savedTemp() {
        try {
            return Integer.parseInt(new Config(this).getString(K_CLIMATE_TEMP));
        } catch (RuntimeException e) {
            return CarCommand.TEMP_DEFAULT;
        }
    }

    private CarCommand.Mode savedMode() {
        try {
            return CarCommand.Mode.valueOf(new Config(this).getString(K_CLIMATE_MODE));
        } catch (RuntimeException e) {
            return CarCommand.Mode.AUTO;
        }
    }

    private void saveClimate(CarCommand c) {
        Config cfg = new Config(this);
        cfg.putString(K_CLIMATE_TEMP, Integer.toString(c.tempC));
        cfg.putString(K_CLIMATE_MODE, c.mode.name());
    }

    /**
     * El botón Clima: la temperatura (− +), el modo (Auto · Frío · Calor) y Encender, Desempañar o Apagar. Empieza con lo
     * de la última vez.
     */
    private void climateDialog() {
        int[] temp = {savedTemp()};
        CarCommand.Mode[] mode = {savedMode()};
        android.content.Context c = this;
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(c);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(Math.round(24 * dp), Math.round(8 * dp), Math.round(24 * dp), 0);
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setGravity(android.view.Gravity.CENTER);
        com.google.android.material.button.MaterialButton minus = new com.google.android.material.button.MaterialButton(c, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        minus.setText("−");
        android.widget.TextView value = new android.widget.TextView(c);
        value.setTextSize(30);
        value.setGravity(android.view.Gravity.CENTER);
        value.setMinWidth(Math.round(110 * dp));
        com.google.android.material.button.MaterialButton plus = new com.google.android.material.button.MaterialButton(c, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        plus.setText("+");
        Runnable paint = () -> value.setText(temp[0] + " °C");
        paint.run();
        minus.setOnClickListener(v -> {
            temp[0] = Math.max(CarCommand.TEMP_MIN, temp[0] - 1);
            paint.run();
        });
        plus.setOnClickListener(v -> {
            temp[0] = Math.min(CarCommand.TEMP_MAX, temp[0] + 1);
            paint.run();
        });
        row.addView(minus);
        row.addView(value);
        row.addView(plus);
        box.addView(row);
        com.google.android.material.button.MaterialButtonToggleGroup modes = new com.google.android.material.button.MaterialButtonToggleGroup(c);
        modes.setSingleSelection(true);
        modes.setSelectionRequired(true);
        int[] labels = {R.string.hql_cc_mode_auto, R.string.hql_cc_mode_cold, R.string.hql_cc_mode_heat};
        CarCommand.Mode[] values = CarCommand.Mode.values();
        for (int i = 0; i < values.length; i++) {
            com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(c, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(android.view.View.generateViewId());
            b.setText(labels[i]);
            CarCommand.Mode m = values[i];
            modes.addView(b, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (m == mode[0]) modes.check(b.getId());
            b.addOnCheckedChangeListener((btn, on) -> {
                if (on) mode[0] = m;
            });
        }
        android.widget.LinearLayout.LayoutParams mlp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Math.round(12 * dp);
        box.addView(modes, mlp);
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hql_cw_climate)
                .setView(box)
                .setPositiveButton(R.string.hql_cc_on, (d, w) -> {
                    CarCommand on = new CarCommand(CarCommand.Kind.CLIMATE_ON, temp[0], mode[0]);
                    saveClimate(on);
                    send(on);
                })
                .setNeutralButton(R.string.hql_cc_defrost, (d, w) -> send(new CarCommand(CarCommand.Kind.DEFROST)))
                .setNegativeButton(R.string.hql_cc_climate_off, (d, w) -> send(new CarCommand(CarCommand.Kind.CLIMATE_OFF))));
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
            CarCommand c = CarCommand.parse(h, savedTemp(), savedMode());
            if (c != null) {
                L.i("widget «Coche»: por voz → " + c);
                if (c.kind == CarCommand.Kind.FIND) {
                    locate();
                } else {
                    if (c.kind == CarCommand.Kind.CLIMATE_ON) saveClimate(c);
                    confirmAndSend(c);
                }
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

    /** «Pitar y luces» solo tiene sentido cerca: a menos de esto (o sin saber dónde está el móvil). */
    static final double HONK_MAX_M = 1000;

    /**
     * Localizar: dónde está el coche (una lectura solo para eso, CarCloudSession.readPosition), a cuánto de ti y de cuándo
     * es el dato; «Ver en el mapa» lo abre en Google Maps (a pie) y, cerca, «Pitar y luces». La posición no se guarda.
     */
    private void locate() {
        message(Str.get(R.string.hql_cc_find_searching), true);
        new Thread(() -> {
            CarCloudSession.Found f = null;
            String error = null;
            try {
                f = CarCloudSession.readPosition(this);
            } catch (Exception e) {
                error = Http.safeError(e);
                L.w("widget «Coche»: «Localizar» fallido: " + error);
            }
            double[] me = RoutePlanner.lastKnown(this);
            CarCloudSession.Found found = f;
            String err = error;
            main.post(() -> {
                if (isFinishing()) return;
                if (found == null) {
                    message(err != null ? Str.get(R.string.hql_cc_failed, Str.get(R.string.hql_cc_find_title), err)
                            : Str.get(R.string.hql_cc_find_none), false);
                    return;
                }
                showWhere(found, me);
            });
        }, "car-locate").start();
    }

    private void showWhere(CarCloudSession.Found f, double[] me) {
        double[] car = f.position.near(me == null ? Double.NaN : me[0], me == null ? Double.NaN : me[1]);
        String age = age(f.dataMs, System.currentTimeMillis());
        double distM = me == null ? Double.NaN : RoadInfo.dist(me[0], me[1], car[0], car[1]);
        String text = Double.isNaN(distM) ? Str.get(R.string.hql_cc_find_where_nophone, age)
                : Str.get(R.string.hql_cc_find_where, CarOrders.distance(distM), age);
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hql_cc_find_title)
                .setMessage(text)
                .setPositiveButton(R.string.hql_cc_find_map, (d, w) -> {
                    openMap(car[0], car[1]);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish());
        if (Double.isNaN(distM) || distM <= HONK_MAX_M) {
            b.setNeutralButton(R.string.hql_cc_find, (d, w) -> send(new CarCommand(CarCommand.Kind.FIND)));
        }
        show(b);
    }

    /** Google Maps con la ruta a pie hasta el coche (o el navegador, si no está Maps). */
    private void openMap(double lat, double lon) {
        String at = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon);
        android.net.Uri u = android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=walking&destination=" + at);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            L.w("widget «Coche»: sin app para el mapa");
        }
    }

    /** De cuándo es el dato: «ahora mismo», «hace 15 min», «hace 2 h», «hace 3 días». */
    static String age(long dataMs, long nowMs) {
        if (dataMs <= 0) return Str.get(R.string.hql_cc_age_unknown);
        long min = Math.max(0, (nowMs - dataMs) / 60_000);
        if (min < 2) return Str.get(R.string.hql_cc_age_now);
        if (min < 60) return Str.get(R.string.hql_cc_age_min, (int) min);
        if (min < 48 * 60) return Str.get(R.string.hql_cc_age_h, (int) (min / 60));
        return Str.get(R.string.hql_cc_age_d, (int) (min / (24 * 60)));
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
                if (c.kind == CarCommand.Kind.CLIMATE_ON || c.kind == CarCommand.Kind.CLIMATE_OFF || c.kind == CarCommand.Kind.DEFROST) {
                    CarOrders.noteClimate(this, c);
                }
            } catch (Exception e) {
                good = false;
                L.w("widget «Coche»: orden " + c + " fallida: " + Http.safeError(e));
                result = Str.get(R.string.hql_cc_failed, c.label(), Http.safeError(e));
            }
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            new Config(this).putString(CarOrders.K_LAST, result + " · " + time);
            CarOrders.refresh(this);
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
