package com.headqlink.link;

import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.support.v4.media.MediaBrowserCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.MediaSessionManager;

import java.util.List;

/**
 * La radio como fuente multimedia de Android Auto (res/xml/automotive_app_desc.xml): Android Auto se conecta aquí,
 * enseña lo que suena con la sesión de RadioSession (emisora, canción y logo) y, en su lista de apps de música,
 * «Qonecta+» con las favoritas y las recientes (RadioLibrary). Solo está habilitado mientras suena la radio
 * (RadioSession, «fuente de Android Auto»): con la radio parada, Android Auto no ve a Qonecta+ como app de música y no
 * salta a ella por las otras sesiones de la app. Instalada fuera de Google Play, Android Auto solo la enseña con
 * «Fuentes desconocidas» activado en sus ajustes de desarrollador. Conectarse no pone nada a sonar: solo elegir una
 * emisora o «▶» (RadioSession). Sin la interfaz propia en marcha (modo «Auto»), lo que se puso desde Android Auto se para
 * cuando se va.
 *
 * Solo entran Android Auto, el sistema y el Bluetooth (RadioLibrary.trusted): el servicio está exportado y las listas
 * dicen qué emisoras escuchas.
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
        // Los demás, fuera del todo: ni las listas ni la sesión (con ella podrían poner emisoras).
        if (!RadioLibrary.trusted(clientPackageName, clientUid, Process.myUid())) {
            L.i("radio: " + clientPackageName + " quiere conectarse a la radio: no es de confianza, no entra");
            return null;
        }
        L.i("radio: " + clientPackageName + " se conecta a la radio");
        return new BrowserRoot(RadioLibrary.ROOT, RadioLibrary.rootExtras());
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        // MediaBrowserServiceCompat da cualquier carpeta que se pida sin mirar a quién se le dio la raíz: se vuelve a
        // mirar quién pide.
        boolean may = callerMayList();
        if (!may) L.w("radio: alguien que no es de confianza pide la carpeta «" + parentId + "»: nada");
        result.sendResult(RadioLibrary.children(this, parentId, may));
    }

    /** Quien pide ahora (getCurrentBrowserInfo) puede ver las listas; si no se sabe quién es, no. */
    private boolean callerMayList() {
        try {
            MediaSessionManager.RemoteUserInfo who = getCurrentBrowserInfo();
            int uid = who.getUid();
            return RadioLibrary.mayList(who.getPackageName(), uid, Process.myUid(),
                    uid < 0 ? null : getPackageManager().getPackagesForUid(uid), Build.VERSION.SDK_INT);
        } catch (RuntimeException e) {
            return false;
        }
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
