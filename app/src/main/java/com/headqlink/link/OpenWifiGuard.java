package com.headqlink.link;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

/**
 * El servidor de Android Auto encendido entre viajes (Config.aaServerKeepOn) escucha en todas las redes: en una Wi-Fi sin
 * contraseña (cafeterías, áreas de servicio…) cualquiera de esa red podría conectarse a él. Al dejarlo encendido se pide
 * a Android que avise de cada Wi-Fi a la que se conecte el móvil (registerNetworkCallback con un PendingIntent: llega
 * también con la app cerrada) y, si es abierta (sin contraseña, o «Enhanced Open»), se apaga (AaServerStarter.requestStop:
 * con el botón de su notificación, sin desbloquear; si no, al desbloquear).
 *
 * Saber si una Wi-Fi tiene contraseña necesita Android 12 (WifiInfo.getCurrentSecurityType); antes no se sabe y el
 * servidor sigue encendido. Nunca se registra el nombre de la red.
 */
final class OpenWifiGuard {
    private OpenWifiGuard() {
    }

    /** Puro: la seguridad de la Wi-Fi (WifiInfo.SECURITY_TYPE_*) es abierta: sin contraseña (OPEN, 0) o OWE (6). */
    static boolean isOpen(int securityType) {
        return securityType == 0 || securityType == 6;
    }

    /** Empieza a vigilar (o sigue: el mismo PendingIntent sustituye al anterior). */
    static void arm(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            cm.registerNetworkCallback(req, pending(ctx));
            L.life("AA server: vigilo las Wi-Fi (se apaga si el móvil entra en una sin contraseña)");
        } catch (RuntimeException e) {
            L.w("AA server: no se pueden vigilar las Wi-Fi (" + e.getClass().getSimpleName() + ")");
        }
    }

    static void disarm(Context ctx) {
        try {
            ctx.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(pending(ctx));
        } catch (RuntimeException ignored) {
            // No estaba registrado.
        }
    }

    private static PendingIntent pending(Context ctx) {
        // Mutable: Android le añade la red (ConnectivityManager.EXTRA_NETWORK).
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, Receiver.class), flags);
    }

    /** ¿Está ahora el móvil en una Wi-Fi sin contraseña? (false si no lo está o no se sabe). */
    static boolean onOpenWifi(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            //noinspection deprecation
            for (Network n : cm.getAllNetworks()) {
                Integer sec = security(cm, n);
                if (sec != null && isOpen(sec)) return true;
            }
        } catch (RuntimeException ignored) {
            // Sin datos de la red: como si no.
        }
        return false;
    }

    /** La seguridad de la Wi-Fi n (WifiInfo.SECURITY_TYPE_*), o null si no es Wi-Fi o no se sabe. */
    private static Integer security(ConnectivityManager cm, Network n) {
        if (Build.VERSION.SDK_INT < 31 || n == null) return null;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
        if (!(nc.getTransportInfo() instanceof android.net.wifi.WifiInfo)) return null;
        int t = ((android.net.wifi.WifiInfo) nc.getTransportInfo()).getCurrentSecurityType();
        return t == android.net.wifi.WifiInfo.SECURITY_TYPE_UNKNOWN ? null : t;
    }

    /** Una Wi-Fi nueva: si el servidor está encendido entre viajes y la red es abierta, se apaga. */
    static void onWifi(Context ctx, Network n) {
        Config cfg = new Config(ctx);
        if (cfg.aaServerManual() || !cfg.aaServerKeepOn() || !AaServerStarter.serverLikelyOn(ctx)) {
            disarm(ctx);
            return;
        }
        // Con el enlace en marcha lo decide su cierre (con la red de ese momento).
        if (LinkState.running) return;
        Integer sec = security(ctx.getSystemService(ConnectivityManager.class), n);
        if (sec == null || !isOpen(sec)) {
            L.i("AA server: Wi-Fi nueva " + (sec == null ? "(no se sabe si tiene contraseña)" : "con contraseña")
                    + ": sigue encendido entre viajes");
            return;
        }
        L.life("AA server: el móvil ha entrado en una Wi-Fi sin contraseña: apago el servidor de Android Auto (estaba"
                + " encendido entre viajes)");
        disarm(ctx);
        AaServerStarter.requestStop(ctx);
    }

    /** Lo que manda Android al conectarse a una Wi-Fi (registrado en arm). */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            L.init(ctx);
            Network n = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
            onWifi(ctx, n);
        }
    }
}
