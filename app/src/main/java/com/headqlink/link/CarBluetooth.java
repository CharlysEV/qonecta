package com.headqlink.link;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * El Bluetooth del coche, aprendido por su dirección: al conectar con el coche, el dispositivo de audio del coche (manos
 * libres o audio de coche) conectado al móvil por A2DP se apunta. Así CarBtReceiver lo reconoce aunque se haya renombrado
 * (el filtro por nombre, «Leapmotor_BT» de serie, no valía con un coche llamado de otra forma: el 26-10, al apagar el coche
 * por Wi-Fi Direct, se esperaron los 5 min de «Esperar al coche» en vez de cerrar al irse su Bluetooth).
 * Solo en el móvil (Config); en el registro, la dirección recortada.
 */
final class CarBluetooth {
    /** Coches que se recuerdan (el último, primero). */
    static final int MAX = 3;

    private CarBluetooth() {
    }

    /** Un dispositivo conectado: su dirección y su clase (BluetoothClass.Device). */
    static final class Seen {
        final String address;
        final int deviceClass;

        Seen(String address, int deviceClass) {
            this.address = address;
            this.deviceClass = deviceClass;
        }
    }

    /** Puro: ¿es de clase coche (audio de coche o manos libres)? Los auriculares y altavoces, no. */
    static boolean carClass(int deviceClass) {
        return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE;
    }

    /** Puro: de los conectados por A2DP, el del coche si hay uno solo de clase coche; si no, null (no se adivina). */
    static String pick(List<Seen> connected) {
        String found = null;
        for (Seen s : connected) {
            if (s.address == null || !carClass(s.deviceClass)) continue;
            if (found != null && !found.equalsIgnoreCase(s.address)) return null;
            found = s.address;
        }
        return found == null ? null : found.toUpperCase(Locale.ROOT);
    }

    /** Puro: la lista con addr delante (sin repetir) y como mucho max. */
    static List<String> remember(List<String> current, String addr, int max) {
        List<String> out = new ArrayList<>();
        out.add(addr.toUpperCase(Locale.ROOT));
        for (String a : current) {
            if (out.size() >= max) break;
            if (!a.isEmpty() && !a.equalsIgnoreCase(addr)) out.add(a.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** «…:12:34» para el registro. */
    static String masked(String addr) {
        if (addr == null || addr.length() < 5) return "…";
        return "…" + addr.substring(addr.length() - 6);
    }

    static List<String> known(Context c) {
        String s = new Config(c).carBtAddresses();
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split(",")));
    }

    /** ¿Es el Bluetooth de un coche aprendido? */
    static boolean isCar(Context c, String address) {
        if (address == null) return false;
        for (String a : known(c)) if (a.equalsIgnoreCase(address)) return true;
        return false;
    }

    /** Con la sesión del coche en marcha: pregunta a Android qué hay conectado por A2DP y apunta el del coche. */
    static void learn(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (android.os.Build.VERSION.SDK_INT >= 31
                && app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        BluetoothManager bm = app.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        try {
            adapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        List<Seen> seen = new ArrayList<>();
                        for (BluetoothDevice d : proxy.getConnectedDevices()) {
                            BluetoothClass bc = d.getBluetoothClass();
                            seen.add(new Seen(d.getAddress(), bc == null ? 0 : bc.getDeviceClass()));
                        }
                        String car = pick(seen);
                        if (car != null && !isCar(app, car)) {
                            new Config(app).setCarBtAddresses(String.join(",", remember(known(app), car, MAX)));
                            L.i("Bluetooth del coche aprendido (" + masked(car) + "): se reconoce aunque tenga otro nombre");
                        }
                    } catch (RuntimeException e) {
                        L.w("Bluetooth: no se pudo ver el del coche (" + e.getClass().getSimpleName() + ")");
                    } finally {
                        adapter.closeProfileProxy(profile, proxy);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.A2DP);
        } catch (RuntimeException e) {
            L.w("Bluetooth: no se pudo preguntar por el del coche (" + e.getClass().getSimpleName() + ")");
        }
    }
}
