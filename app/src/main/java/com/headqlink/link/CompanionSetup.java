package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.utils.ToastUtils;

/**
 * Menú ⚙ › «Compañero de voz (IA)» en el móvil: qué es, lo que cuesta y lo que se manda, la clave de API de Anthropic
 * del usuario (se guarda cifrada; no se vuelve a enseñar), Opus o el modo rápido (Haiku) y dónde se enciende en el coche.
 */
final class CompanionSetup {
    static final String KEYS_URL = "https://console.anthropic.com/settings/keys";

    private CompanionSetup() {
    }

    static void show(Activity a) {
        if (!ClaudeCompanion.supported()) {
            ToastUtils.showToast(a, Str.get(R.string.hql_companion_old_android), Toast.LENGTH_LONG, true);
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        int pad = Math.round(22 * d);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, Math.round(8 * d), pad, 0);
        TextView text = new TextView(a);
        text.setText(Str.get(R.string.hql_companion_setup_text));
        text.setTextColor(a.getColor(R.color.hql_text_dim));
        col.addView(text);
        com.google.android.material.textfield.TextInputLayout box = new com.google.android.material.textfield.TextInputLayout(a);
        box.setHint(Str.get(ClaudeCompanion.hasKey(a) ? R.string.hql_companion_key_saved : R.string.hql_companion_key_hint));
        box.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        com.google.android.material.textfield.TextInputEditText key = new com.google.android.material.textfield.TextInputEditText(box.getContext());
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSingleLine(true);
        box.addView(key);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Math.round(12 * d);
        col.addView(box, blp);
        com.google.android.material.materialswitch.MaterialSwitch fast = new com.google.android.material.materialswitch.MaterialSwitch(a);
        fast.setText(Str.get(R.string.hql_companion_fast));
        fast.setChecked(ClaudeCompanion.fast(a));
        col.addView(fast, blp);
        TextView get = new TextView(a);
        get.setText(Str.get(R.string.hql_companion_get_key));
        get.setTextColor(a.getColor(R.color.hql_accent));
        get.setPadding(0, Math.round(10 * d), 0, Math.round(4 * d));
        get.setOnClickListener(v -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(KEYS_URL)));
            } catch (RuntimeException e) {
                L.w("compañero: no se pudo abrir la consola de Anthropic");
            }
        });
        col.addView(get);
        TextView where = new TextView(a);
        where.setText(Str.get(R.string.hql_companion_enable_hint));
        where.setTextColor(a.getColor(R.color.hql_text_faint));
        where.setPadding(0, Math.round(6 * d), 0, 0);
        col.addView(where);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(col);
        com.google.android.material.dialog.MaterialAlertDialogBuilder b = new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                .setTitle(Str.get(R.string.hql_companion_menu))
                .setView(sv)
                .setPositiveButton(Str.get(R.string.hql_companion_save), null)
                .setNegativeButton(android.R.string.cancel, null);
        if (ClaudeCompanion.hasKey(a)) {
            b.setNeutralButton(Str.get(R.string.hql_companion_delete), (x, w) -> {
                ClaudeCompanion.saveKey(a, "");
                ToastUtils.showToast(a, Str.get(R.string.hql_companion_deleted), Toast.LENGTH_SHORT, true);
            });
        }
        androidx.appcompat.app.AlertDialog dlg = b.show();
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String k = key.getText() == null ? "" : key.getText().toString().trim();
            ClaudeCompanion.setFast(a, fast.isChecked());
            if (!k.isEmpty()) {
                if (!ClaudeCompanion.looksLikeKey(k)) {
                    box.setError(Str.get(R.string.hql_companion_key_invalid));
                    return;
                }
                if (!ClaudeCompanion.saveKey(a, k)) {
                    box.setError(Str.get(R.string.hql_companion_failed));
                    return;
                }
                ToastUtils.showToast(a, Str.get(R.string.hql_companion_key_saved), Toast.LENGTH_SHORT, true);
            }
            dlg.dismiss();
        });
    }
}
