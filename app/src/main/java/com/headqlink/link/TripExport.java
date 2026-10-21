package com.headqlink.link;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * «Exportar viajes» (pestaña Viajes del móvil): un CSV con una línea por viaje (fecha, horas, km, kWh, consumo, de
 * dónde sale el consumo, coste, % al salir y al llegar, subida, bajada, velocidad máxima y gasolina) y, si se pide, un
 * GPX con el recorrido de cada viaje, juntos en un ZIP. El CSV va en el formato del idioma de la app para que Excel lo
 * abra sin más: con coma decimal, separado por «;». Se comparte con la hoja de Android (FileProvider, en la caché).
 * Las cifras, las mismas que enseña la app (TripStats).
 */
final class TripExport {
    private TripExport() {
    }

    /** Una línea del CSV, ya con las cuentas hechas (NaN: vacío). */
    static final class Row {
        long startMs;
        long endMs;
        double km;
        long minutes;
        double kwh;
        double kwhPer100;
        String source;
        double cost;
        double socStart;
        double socEnd;
        double climb;
        double descent;
        double maxKmh;
        double fuelL;
    }

    /** La línea de un viaje con las cuentas de la app: el consumo del coche, el real o el estimado, y su coste. */
    static Row row(TripLog.Trip t, List<CloudHistory.Trip> history, double elecPrice, double fuelPrice) {
        Row r = new Row();
        r.startMs = t.startMs;
        r.endMs = t.endMs();
        r.km = t.km;
        r.minutes = t.minutes;
        r.kwhPer100 = TripStats.bestKwhPer100(t, history);
        r.kwh = !Double.isNaN(r.kwhPer100) && t.km > 0 ? r.kwhPer100 * t.km / 100 : t.kwh;
        TripStats.Source src = TripStats.source(t, history);
        r.source = Str.get(src == TripStats.Source.CAR ? R.string.hql_trip_source_car
                : src == TripStats.Source.REAL ? R.string.hql_trip_source_real : R.string.hql_trip_source_estimated);
        r.cost = TripStats.cost(t, history, elecPrice, fuelPrice);
        r.socStart = t.socStart;
        r.socEnd = t.socEnd;
        r.climb = t.climb;
        r.descent = t.descent;
        r.maxKmh = t.maxKmh;
        r.fuelL = TripStats.fuelL(t, history);
        return r;
    }

    /**
     * Puro: el CSV (con BOM, para que Excel vea las tildes). header: los nombres de las columnas separados por «|», en el
     * idioma de la app. Con coma decimal en loc, separado por «;»; si no, por «,».
     */
    static String csv(List<Row> rows, String header, Locale loc, TimeZone tz) {
        char dec = DecimalFormatSymbols.getInstance(loc).getDecimalSeparator();
        String sep = dec == ',' ? ";" : ",";
        StringBuilder b = new StringBuilder("﻿");
        String[] cols = header.split("\\|");
        for (int i = 0; i < cols.length; i++) b.append(i > 0 ? sep : "").append(quote(cols[i], sep));
        b.append("\r\n");
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        day.setTimeZone(tz);
        hm.setTimeZone(tz);
        for (Row r : rows) {
            String[] v = {
                    day.format(new Date(r.startMs)),
                    hm.format(new Date(r.startMs)),
                    hm.format(new Date(r.endMs)),
                    num(r.km, 1, loc),
                    String.valueOf(r.minutes),
                    num(r.kwh, 2, loc),
                    num(r.kwhPer100, 1, loc),
                    r.source == null ? "" : r.source,
                    num(r.cost, 2, loc),
                    num(r.socStart, 1, loc),
                    num(r.socEnd, 1, loc),
                    num(r.climb, 0, loc),
                    num(r.descent, 0, loc),
                    num(r.maxKmh, 0, loc),
                    r.fuelL > 0 ? num(r.fuelL, 1, loc) : "",
            };
            for (int i = 0; i < v.length; i++) b.append(i > 0 ? sep : "").append(quote(v[i], sep));
            b.append("\r\n");
        }
        return b.toString();
    }

    /** Puro: el recorrido de un viaje en GPX 1.1 (puntos con su hora si se sabe el segundo de cada uno). */
    static String gpx(TripLog.Trip t, String name) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<gpx version=\"1.1\" creator=\"Qonecta+\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        b.append("  <trk>\n    <name>").append(xml(name)).append("</name>\n    <trkseg>\n");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        if (t.track != null) {
            for (double[] p : t.track) {
                if (p == null || p.length < 2) continue;
                b.append(String.format(Locale.ROOT, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">", p[0], p[1]));
                if (p.length > 2 && !Double.isNaN(p[2])) {
                    b.append("<time>").append(iso.format(new Date(t.startMs + Math.round(p[2] * 1000)))).append("</time>");
                }
                b.append("</trkpt>\n");
            }
        }
        b.append("    </trkseg>\n  </trk>\n</gpx>\n");
        return b.toString();
    }

    /** Puro: el nombre de un viaje para su GPX y su fichero («2026-10-21 08.52»). */
    static String name(long startMs, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT);
        f.setTimeZone(tz);
        return f.format(new Date(startMs));
    }

    private static String num(double v, int decimals, Locale loc) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return String.format(loc, "%." + decimals + "f", v);
    }

    private static String quote(String s, String sep) {
        if (s.contains(sep) || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ fichero y hoja de compartir

    /**
     * Escribe el CSV (y, con tracks, un ZIP con el CSV y un GPX por viaje con recorrido) en la caché y devuelve la hoja
     * de compartir. Hilo de fondo.
     */
    static Intent export(Context ctx, List<TripLog.Trip> trips, List<CloudHistory.Trip> history, boolean tracks) throws IOException {
        Config cfg = new Config(ctx);
        double elec = cfg.electricityPrice();
        double fuel = cfg.fuelPrice();
        java.util.ArrayList<Row> rows = new java.util.ArrayList<>();
        // Del más antiguo al más reciente, como se lee una hoja de cálculo.
        for (int i = trips.size() - 1; i >= 0; i--) {
            TripLog.Trip t = trips.get(i);
            if (t.km > 0) rows.add(row(t, history, elec, fuel));
        }
        TimeZone tz = TimeZone.getDefault();
        String csv = csv(rows, Str.get(R.string.hql_trips_export_header), Str.locale(), tz);
        String stamp = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        File dir = new File(ctx.getCacheDir(), "export");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no se pudo crear " + dir.getName());
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File out;
        String mime;
        int gpx = 0;
        if (!tracks) {
            out = new File(dir, "qonecta-viajes-" + stamp + ".csv");
            mime = "text/csv";
            try (OutputStream o = new FileOutputStream(out)) {
                o.write(csv.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            out = new File(dir, "qonecta-viajes-" + stamp + ".zip");
            mime = "application/zip";
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
                z.putNextEntry(new ZipEntry("viajes.csv"));
                z.write(csv.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                for (TripLog.Trip t : trips) {
                    if (t.track == null || t.track.length < 2) continue;
                    String n = name(t.startMs, tz);
                    z.putNextEntry(new ZipEntry("recorridos/" + n + ".gpx"));
                    z.write(gpx(t, n).getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                    gpx++;
                }
            }
        }
        L.i("viajes: exportados " + rows.size() + " en CSV" + (tracks ? " y " + gpx + " recorridos en GPX" : ""));
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", out);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Qonecta+: " + Str.get(R.string.hql_trips_export))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(out.getName(), uri));
        return Intent.createChooser(send, Str.get(R.string.hql_trips_export));
    }
}
