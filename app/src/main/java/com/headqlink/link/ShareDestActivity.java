package com.headqlink.link;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * «Ruta en Qonecta+» en Compartir (Google Maps, WhatsApp, una dirección) y para los enlaces «geo:»: saca el sitio
 * (SharedDest), lo guarda para el próximo viaje y lo dice; si la sesión con el coche ya está en marcha, pone la ruta
 * ya (RoutePlanner.useShared). Transparente; el sitio no se escribe en el registro.
 */
public final class ShareDestActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        if (b != null) return;
        Intent i = getIntent();
        String text = Intent.ACTION_SEND.equals(i.getAction()) ? i.getStringExtra(Intent.EXTRA_TEXT) : null;
        String subject = Intent.ACTION_SEND.equals(i.getAction()) ? i.getStringExtra(Intent.EXTRA_SUBJECT) : null;
        Uri data = i.getData();
        String geo = data != null ? data.toString() : null;
        if (text == null && geo == null) {
            finish();
            return;
        }
        show(new MaterialAlertDialogBuilder(this).setMessage(R.string.hql_share_working), false);
        new Thread(() -> {
            RoutePlanner.Place p = null;
            try {
                p = place(text, subject, geo);
            } catch (Exception e) {
                L.w("destino compartido: " + e.getClass().getSimpleName());
            }
            RoutePlanner.Place found = p;
            main.post(() -> done(found));
        }, "share-dest").start();
    }

    /** El sitio de lo compartido: coordenadas del enlace o del «geo:» o, si no, la dirección buscada. null si nada. */
    private RoutePlanner.Place place(String text, String subject, String geo) throws Exception {
        String label = null;
        String query = null;
        double[] at = null;
        if (geo != null) {
            at = SharedDest.coords(geo);
            label = SharedDest.geoLabel(geo);
            if (at == null) query = SharedDest.query(geo);
        } else {
            label = SharedDest.name(text);
            if (label == null && subject != null && !subject.trim().isEmpty()) label = subject.trim();
            String url = SharedDest.firstUrl(text);
            if (url != null && SharedDest.mapsUrl(url)) {
                String full = SharedDest.shortUrl(url) ? SharedDest.resolve(url) : url;
                at = SharedDest.coords(full);
                if (at == null) query = SharedDest.query(full);
            } else if (url == null) {
                query = text.trim();
            }
            if (at == null && query == null && label != null) query = label;
        }
        if (at == null && query != null && !query.isEmpty()) {
            double[] me = RoutePlanner.lastKnown(this);
            List<RoutePlanner.Place> found = PlaceSearch.geocoder(this, query, me == null ? Double.NaN : me[0], me == null ? Double.NaN : me[1]);
            if (found.isEmpty()) found = PlaceSearch.photon(query, me == null ? Double.NaN : me[0], me == null ? Double.NaN : me[1]);
            if (!found.isEmpty()) {
                RoutePlanner.Place f = found.get(0);
                if (label == null) label = f.name;
                f.name = label;
                return f;
            }
            return null;
        }
        if (at == null) return null;
        RoutePlanner.Place p = new RoutePlanner.Place();
        p.name = label != null && !label.isEmpty() ? label : getString(R.string.hql_share_place);
        p.detail = "";
        p.lat = at[0];
        p.lon = at[1];
        return p;
    }

    private void done(RoutePlanner.Place p) {
        if (isFinishing()) return;
        if (p == null) {
            L.i("destino compartido: no se encontró ningún sitio");
            show(new MaterialAlertDialogBuilder(this).setMessage(R.string.hql_share_failed)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish()), true);
            return;
        }
        String msg;
        if (RoutePlanner.get() != null) {
            // Ya en el coche (Auto extendido): la ruta, ahora.
            L.i("destino compartido: con la sesión en marcha, la pongo ya");
            RoutePlanner.useShared(getApplicationContext(), p);
            msg = getString(R.string.hql_share_now, p.name);
        } else {
            SharedDest.save(this, p);
            L.i("destino compartido: guardado para el próximo viaje");
            msg = getString(R.string.hql_share_saved, p.name);
        }
        show(new MaterialAlertDialogBuilder(this).setMessage(msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish()), true);
    }

    private void show(MaterialAlertDialogBuilder b, boolean cancelable) {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        dialog = b.create();
        dialog.setCancelable(cancelable);
        dialog.setOnDismissListener(d -> {
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
