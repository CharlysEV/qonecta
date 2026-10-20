package com.headqlink.link;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceRequest;
import android.os.Handler;
import android.os.Looper;

import com.andrerinas.openheadunit.R;

/**
 * Une el móvil al grupo WiFi Direct del coche (el coche es Group Owner).
 * Descubrimiento UPnP (servicio del coche) y connect con groupOwnerIntent=0.
 * Como respaldo, cualquier peer con el nombre de dispositivo del coche (NAME_PREFIX).
 */
@SuppressLint("MissingPermission")
final class P2pLink {
    /**
     * Frecuencia del grupo Wi-Fi Direct del coche (MHz), o 0 si no se sabe. A diferencia de la zona Wi-Fi (que pide un
     * permiso de sistema), la del grupo P2P se puede leer (WifiP2pGroup.getFrequency, Android 10+): banda en el resumen
     * de la sesión y en sessions.csv.
     */
    static volatile int groupFreqMhz;

    private static final String UPNP_MARK = "QDLink_UPnP_Device";
    private static final String NAME_PREFIX = "LeapMotor";
    private static final long RETRY_MS = 15_000;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiP2pManager mgr;
    private WifiP2pManager.Channel ch;
    private volatile boolean inGroup;
    /**
     * Hay sesión con el coche (por la vía que sea). 20-10, con la tablet por el Wi-Fi de casa y la app en Wi-Fi Direct: sin
     * grupo, se buscaba el coche cada RETRY_MS durante toda la sesión (125 veces en media hora) y cada búsqueda sacaba la
     * radio del canal: picos de hasta 1,7 s. Con sesión, no se busca.
     */
    private volatile boolean carSession;
    private boolean connecting;
    private boolean started;
    /** Lo último publicado en la fila «Red» (para {@link #republish}). */
    private volatile LinkState.Level lastLevel = LinkState.Level.IDLE;
    private volatile String lastText = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String a = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                WifiP2pInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
                onInfo(info);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                if (!inGroup && !connecting && !carSession) mgr.requestPeers(ch, list -> {
                    for (WifiP2pDevice d : list.getDeviceList()) {
                        if (d.deviceName != null && d.deviceName.startsWith(NAME_PREFIX)) {
                            L.i("P2P peer coche: " + d.deviceName + " " + HotspotRadio.Companion.maskMac(d.deviceAddress) + " status=" + d.status);
                            connect(d);
                            return;
                        }
                    }
                });
            }
        }
    };

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (!inGroup && !carSession) {
                connecting = false;
                discover();
            }
            handler.postDelayed(this, RETRY_MS);
        }
    };

    P2pLink(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean isInGroup() {
        return inGroup;
    }

    private void publish(LinkState.Level level, String text) {
        lastLevel = level;
        lastText = text;
        LinkState.setNetwork(level, text);
    }

    /** Vuelve a publicar el estado del grupo (p. ej. tras un error del UDP que ocupó la fila «Red»). */
    void republish() {
        LinkState.setNetwork(lastLevel, lastText);
    }

    void start() {
        if (started) return;
        started = true;
        groupNoted = false;
        mgr = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        ch = mgr.initialize(ctx, Looper.getMainLooper(), () -> L.w("P2P channel perdido"));
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);

        mgr.setUpnpServiceResponseListener(ch, (services, device) -> {
            for (String s : services) {
                if (s.contains(UPNP_MARK)) {
                    L.i("UPnP del coche " + device.deviceName + " " + HotspotRadio.Companion.maskMac(device.deviceAddress) + ": " + s);
                    connect(device);
                    return;
                }
            }
        });
        // Solo descubrimos si no estamos ya en el grupo del coche.
        mgr.requestConnectionInfo(ch, info -> {
            onInfo(info);
            handler.post(retry);
        });
    }

    private void onInfo(WifiP2pInfo info) {
        boolean was = inGroup;
        inGroup = info != null && info.groupFormed && !info.isGroupOwner;
        if (inGroup && !was) {
            connecting = false;
            L.i("P2P en grupo del coche, GO=" + info.groupOwnerAddress + "; paro el descubrimiento");
            publish(LinkState.Level.OK, Str.get(R.string.hql_p2p_in_group,
                    info.groupOwnerAddress != null ? info.groupOwnerAddress.getHostAddress() : "?"));
            // El descubrimiento P2P saca la radio del canal y provoca picos de 100-350 ms.
            mgr.stopPeerDiscovery(ch, null);
            mgr.clearServiceRequests(ch, null);
            readGroupBand();
        } else if (!inGroup && was) {
            groupFreqMhz = 0;
            L.w("P2P fuera del grupo");
            publish(LinkState.Level.BUSY, Str.get(R.string.hql_p2p_searching));
        } else if (info != null && info.groupFormed && info.isGroupOwner) {
            L.w("P2P: el móvil es GO de otro grupo; el protocolo espera que el coche sea GO");
        }
    }

    /** Banda y canal del grupo del coche (los elige el coche, que es el dueño del grupo). */
    @SuppressLint("MissingPermission")
    private void readGroupBand() {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            mgr.requestGroupInfo(ch, g -> {
                if (g == null) return;
                int mhz = g.getFrequency();
                groupFreqMhz = mhz;
                String band = HotspotRadio.Companion.bandOf(mhz);
                if (band == null) {
                    L.i("P2P: grupo del coche sin frecuencia conocida (" + mhz + " MHz)");
                    return;
                }
                L.i("P2P: grupo del coche en " + band + " · canal " + HotspotRadio.Companion.channelOf(mhz) + " (" + mhz + " MHz)");
                if (HotspotRadio.BAND_24.equals(band)) {
                    L.w("P2P: el grupo del coche va en 2,4 GHz: más lento y con más cortes que en 5 GHz (lo elige el coche)");
                }
            });
        } catch (RuntimeException e) {
            L.w("P2P: no se pudo leer la banda del grupo: " + e.getMessage());
        }
    }

    private void discover() {
        L.i("P2P buscando coche (UPnP + peers)...");
        publish(LinkState.Level.BUSY, Str.get(R.string.hql_p2p_searching));
        mgr.clearServiceRequests(ch, null);
        mgr.addServiceRequest(ch, WifiP2pUpnpServiceRequest.newInstance(), listener("addServiceRequest"));
        mgr.discoverServices(ch, listener("discoverServices"));
        mgr.discoverPeers(ch, listener("discoverPeers"));
    }

    /**
     * Pide unirse al grupo del coche, salvo que ya haya sesión con él o un grupo Wi-Fi Direct en marcha. 20-10, un
     * Galaxy A52s (Android 14) con un T03: la sesión iba por p2p-wlan0-0 pero Android no decía que hubiera grupo, se
     * pedía connect con el coche ya conectado y, medio segundo después, se caía surfaceflinger (el móvil recargaba el
     * escritorio y todas las apps); otras veces se cortaba Android Auto o fallaba (reason=2) cada pocos segundos.
     */
    private void connect(WifiP2pDevice d) {
        if (connecting || inGroup || carSession) return;
        String iface = p2pInterfaceUp();
        if (iface != null) {
            if (!groupNoted) {
                groupNoted = true;
                L.i("P2P: ya hay un grupo Wi-Fi Direct en marcha (" + iface + "): no pido otra conexión al coche");
            }
            return;
        }
        connecting = true;
        WifiP2pConfig cfg = new WifiP2pConfig();
        cfg.deviceAddress = d.deviceAddress;
        cfg.groupOwnerIntent = 0;
        L.i("P2P connect -> " + d.deviceName + " " + HotspotRadio.Companion.maskMac(d.deviceAddress));
        mgr.connect(ch, cfg, listener("connect"));
    }

    /** Ya dicho en el log que hay un grupo Wi-Fi Direct sin que Android lo cuente (una vez por arranque). */
    private boolean groupNoted;

    /** Puro: ¿es una interfaz de Wi-Fi Direct («p2p-wlan0-0», «p2p0»…)? */
    static boolean isP2pInterface(String name) {
        return name != null && name.startsWith("p2p");
    }

    /** Una interfaz de Wi-Fi Direct encendida y con dirección IPv4 (un grupo en marcha), o null. */
    private static String p2pInterfaceUp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> all = java.net.NetworkInterface.getNetworkInterfaces();
            if (all == null) return null;
            for (java.net.NetworkInterface ni : java.util.Collections.list(all)) {
                if (!isP2pInterface(ni.getName()) || !ni.isUp()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) return ni.getName();
                }
            }
        } catch (java.net.SocketException | RuntimeException ignored) {
        }
        return null;
    }

    private WifiP2pManager.ActionListener listener(String what) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                L.i("P2P " + what + " ok");
            }

            @Override
            public void onFailure(int reason) {
                L.w("P2P " + what + " fallo reason=" + reason);
                if ("connect".equals(what)) connecting = false;
            }
        };
    }

    /** El coche se conecta (true) o se pierde (false). Con sesión no se busca, y lo que se estaba buscando se para. */
    void carSession(boolean on) {
        if (carSession == on) return;
        carSession = on;
        if (on && started && !inGroup) {
            connecting = false;
            L.i("P2P: sesión con el coche sin grupo Wi-Fi Direct: dejo de buscar mientras dure");
            mgr.stopPeerDiscovery(ch, null);
            mgr.clearServiceRequests(ch, null);
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(retry);
        try {
            ctx.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        mgr.clearServiceRequests(ch, null);
        mgr.stopPeerDiscovery(ch, null);
        ch.close();
    }
}
