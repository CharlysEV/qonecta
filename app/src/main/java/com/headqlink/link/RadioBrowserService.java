package com.headqlink.link;

import android.os.Bundle;
import android.os.Process;
import android.support.v4.media.MediaBrowserCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;

import java.util.List;

/**
 * La radio como fuente multimedia de Android Auto (res/xml/automotive_app_desc.xml): Android Auto se conecta aquí,
 * enseña lo que suena con la sesión de RadioSession (emisora, canción y logo) y, en su lista de apps de música,
 * «Qonecta+» con las favoritas y las recientes (RadioLibrary). Instalada fuera de Google Play, Android Auto solo la
 * enseña con «Fuentes desconocidas» activado en sus ajustes de desarrollador. Conectarse no pone nada a sonar: solo
 * elegir una emisora o «▶» (RadioSession). Sin la interfaz propia en marcha (modo «Auto»), lo que se puso desde Android
 * Auto se para cuando se va.
 */
public final class RadioBrowserService extends MediaBrowserServiceCompat {
    private static RadioBrowserService live;

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        setSessionToken(RadioSession.browserUp(this));
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        // Los controles del sistema piden «lo último» para ofrecer seguir con la música más tarde: así, la radio sonaría
        // con un toque fuera del coche. No.
        if (rootHints != null && rootHints.getBoolean(BrowserRoot.EXTRA_RECENT)) return null;
        boolean trusted = RadioLibrary.trusted(clientPackageName, clientUid, Process.myUid());
        L.i("radio: " + clientPackageName + " se conecta a la radio" + (trusted ? "" : " (sin listas)"));
        return new BrowserRoot(trusted ? RadioLibrary.ROOT : RadioLibrary.EMPTY_ROOT, RadioLibrary.rootExtras());
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(RadioLibrary.children(this, parentId));
    }

    @Override
    public void onDestroy() {
        if (live == this) live = null;
        RadioSession.browserDown();
        // Sin la interfaz propia (que la para al cerrarse), lo puesto desde Android Auto se para con él, y se olvida lo
        // parado a mano: en la próxima conexión, un «▶» de Android Auto no lo vuelve a poner.
        if (!CarUi.running()) {
            if (RadioPlayer.current() != null) L.i("radio: Android Auto se ha ido sin la interfaz propia en marcha: paro la radio");
            RadioPlayer.stop();
        }
        super.onDestroy();
    }

    /** Las favoritas o las recientes han cambiado: Android Auto, si está conectado, las vuelve a pedir. */
    static void changed(String parentId) {
        RadioBrowserService s = live;
        if (s != null) s.notifyChildrenChanged(parentId);
    }
}
