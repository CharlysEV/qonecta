package com.headqlink.link;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla de la guía del camino (RoadGuide): arriba, el título, el estado y la voz; una tarjeta grande con la foto, el
 * nombre, a cuánto y a qué lado queda, el resumen, «Contar» y «Llévame»; y la lista de lo que viene (o, parado, de lo que
 * hay cerca a pie). Con la pantalla completa, la tarjeta y la lista van lado a lado; con la pantalla partida (junto a AA),
 * una debajo de otra.
 */
final class GuideScreen implements CarScreen, RoadGuide.Listener {
    /** Desde este ancho (px del lienzo), tarjeta y lista lado a lado; más estrecho (pantalla partida), en una columna. */
    static final int WIDE_MIN = 1200;

    /** false: sin la guía en marcha (las pruebas le pasan el estado con onGuide). */
    private final boolean live;
    private Host host;
    private boolean wide;
    private TextView status;
    private TextView stop;
    private TextView voice;
    private FrameLayout bodyHost;
    private FrameLayout cardBox;
    private TextView listTitle;
    private LinearLayout listBox;
    private RoadGuide.Place cardPlace;
    /** La tarjeta se hizo con resumen (si llega después, se rehace). */
    private boolean cardText;
    private TextView cardMeta;
    private final List<Long> rowIds = new ArrayList<>();
    private final List<TextView> rowMetas = new ArrayList<>();
    /** Lo último enseñado (para rehacerlo al cambiar de ancho). */
    private RoadGuide.State shown = new RoadGuide.State();
    private boolean destroyed;

    GuideScreen() {
        this(true);
    }

    GuideScreen(boolean live) {
        this.live = live;
    }

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        wide = h.width() >= WIDE_MIN;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(28, 20, 28, 12);
        col.setBackgroundColor(CarKit.BG);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = CarStyle.text(c, Str.get(R.string.hql_guide_title), 32, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        status = CarStyle.text(c, "", 21, CarStyle.TEXT_DIM);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);
        titles.addView(status);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // «Parar»: solo mientras la guía habla; la corta al momento (la voz sigue encendida para los siguientes).
        stop = CarStyle.pill(c, "\u25A0  " + Str.get(R.string.hql_guide_stop));
        stop.setBackground(CarStyle.round(CarStyle.BAD, 28));
        stop.setTextColor(CarStyle.ON_ACCENT);
        stop.setOnClickListener(v -> {
            RoadGuide.hush(c);
            if (!live) render(copyQuiet(shown));
        });
        head.addView(stop);
        voice = CarStyle.pill(c, "");
        voice.setOnClickListener(v -> RoadGuide.setVoice(c, !RoadGuide.voiceOn(c)));
        head.addView(voice);
        col.addView(head);

        bodyHost = new FrameLayout(c);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = 16;
        col.addView(bodyHost, bodyLp);
        buildBody(c);
        // Al partir la pantalla (o volver a completa) cambia el ancho sin rehacer la pantalla: se recoloca.
        col.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean w = r - l >= WIDE_MIN;
            if (destroyed || r - l == or - ol || w == wide) return;
            wide = w;
            // Por el Host (el hilo principal, después de esta pasada de medidas), no View.post: va también sin ventana.
            host.post(() -> {
                if (destroyed) return;
                buildBody(c);
                render(shown);
            });
        });
        TextView source = CarStyle.text(c, Str.get(R.string.hql_guide_source), 17, CarStyle.TEXT_DIM);
        source.setPadding(6, 8, 0, 0);
        col.addView(source);

        render(new RoadGuide.State());
        if (live) RoadGuide.start(c, this);
        return col;
    }

    /** Tarjeta y lista: lado a lado (ancho) o una debajo de otra (estrecho). Vacías: las llena render. */
    private void buildBody(Context c) {
        bodyHost.removeAllViews();
        cardPlace = null;
        cardMeta = null;
        rowIds.clear();
        rowMetas.clear();
        cardBox = new FrameLayout(c);
        listTitle = CarStyle.label(c, "");
        listTitle.setPadding(6, 0, 0, 8);
        listBox = new LinearLayout(c);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout listCol = new LinearLayout(c);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listCol.addView(listTitle);
        listCol.addView(listBox);
        if (wide) {
            LinearLayout body = new LinearLayout(c);
            body.setOrientation(LinearLayout.HORIZONTAL);
            ScrollView cardScroll = new ScrollView(c);
            cardScroll.addView(cardBox);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f);
            cl.rightMargin = 22;
            body.addView(cardScroll, cl);
            ScrollView listScroll = new ScrollView(c);
            listScroll.addView(listCol);
            body.addView(listScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            bodyHost.addView(body, CarStyle.match());
        } else {
            ScrollView sv = new ScrollView(c);
            LinearLayout inner = new LinearLayout(c);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.addView(cardBox);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ll.topMargin = 20;
            inner.addView(listCol, ll);
            sv.addView(inner);
            bodyHost.addView(sv, CarStyle.match());
        }
    }

    @Override
    public void destroy() {
        destroyed = true;
        if (live) RoadGuide.stop(this);
    }

    @Override
    public void onGuide(RoadGuide.State s) {
        if (!destroyed) render(s);
    }

    private void render(RoadGuide.State s) {
        shown = s;
        Context c = host.context();
        voice.setText(Str.get(s.voice ? R.string.hql_guide_voice_on : R.string.hql_guide_voice_off));
        voice.setBackground(s.voice ? CarStyle.accent(28) : CarStyle.round(CarStyle.PILL_BG, 28));
        voice.setTextColor(s.voice ? CarStyle.ON_ACCENT : CarStyle.TEXT);
        stop.setVisibility(s.talking ? View.VISIBLE : View.GONE);
        status.setText(statusText(s));
        // La tarjeta: se rehace al cambiar de sitio; si es el mismo, solo la distancia.
        if (s.current == null) {
            cardPlace = null;
            cardMeta = null;
            cardBox.removeAllViews();
            cardBox.addView(CarStyle.message(c, emptyText(s)), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (cardPlace == null || cardPlace.id != s.current.id || cardText != !s.current.extract.isEmpty()) {
            cardPlace = s.current;
            cardText = !s.current.extract.isEmpty();
            cardBox.removeAllViews();
            cardBox.addView(card(c, s.current, s));
        } else if (cardMeta != null) {
            cardMeta.setText(meta(s.current, s));
        }
        listTitle.setText(Str.get(s.moving ? R.string.hql_guide_ahead_list : R.string.hql_guide_near_list));
        listTitle.setVisibility(s.list.isEmpty() ? View.GONE : View.VISIBLE);
        List<Long> ids = new ArrayList<>();
        for (RoadGuide.Place p : s.list) ids.add(p.id);
        if (ids.equals(rowIds)) {
            for (int i = 0; i < s.list.size(); i++) rowMetas.get(i).setText(meta(s.list.get(i), s));
            return;
        }
        rowIds.clear();
        rowIds.addAll(ids);
        rowMetas.clear();
        listBox.removeAllViews();
        for (RoadGuide.Place p : s.list) listBox.addView(row(c, p, s));
    }

    private static String statusText(RoadGuide.State s) {
        switch (s.status) {
            case WAITING_GPS:
                return Str.get(R.string.hql_guide_waiting_gps);
            case LOADING:
                return Str.get(R.string.hql_guide_loading);
            case OFFLINE:
                return Str.get(R.string.hql_guide_offline);
            default:
                return Str.get(s.moving ? R.string.hql_guide_hint_moving : R.string.hql_guide_hint_stopped);
        }
    }

    private static String emptyText(RoadGuide.State s) {
        switch (s.status) {
            case WAITING_GPS:
                return Str.get(R.string.hql_guide_waiting_gps);
            case LOADING:
                return Str.get(R.string.hql_guide_loading);
            case OFFLINE:
                return Str.get(R.string.hql_guide_offline);
            default:
                return Str.get(s.moving ? R.string.hql_guide_none : R.string.hql_guide_none_here);
        }
    }

    /** «3,2 km · a la derecha» en marcha; «800 m · 13 min a pie» parado. */
    static String meta(RoadGuide.Place p, RoadGuide.State s) {
        if (Double.isNaN(s.lat)) return "";
        double km = RoadInfo.dist(s.lat, s.lon, p.lat, p.lon) / 1000;
        String d = RoadGuide.distText(km, Str.locale());
        if (!s.moving) return d + " · " + Str.get(R.string.hql_guide_walk, RoadGuide.walkMin(km));
        int side = RoadGuide.side(RoadGuide.rel(s.heading, RoadInfo.bearing(s.lat, s.lon, p.lat, p.lon)));
        int text = side == RoadGuide.RIGHT ? R.string.hql_guide_side_right : side == RoadGuide.LEFT ? R.string.hql_guide_side_left
                : side == RoadGuide.AHEAD ? R.string.hql_guide_side_ahead : R.string.hql_guide_side_behind;
        return d + " · " + Str.get(text);
    }

    private View card(Context c, RoadGuide.Place p, RoadGuide.State s) {
        LinearLayout card = CarStyle.card(c);
        // La foto, si la hay (sin ella, más sitio para el texto).
        if (!p.thumb.isEmpty()) {
            ImageView img = new ImageView(c);
            img.setBackground(CarStyle.round(CarStyle.ITEM_BG, 22));
            img.setClipToOutline(true);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wide ? 330 : 250);
            il.bottomMargin = 18;
            card.addView(img, il);
            thumb(c, img, p);
        }
        TextView name = CarStyle.text(c, p.title, 34, CarStyle.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);
        cardMeta = CarStyle.text(c, meta(p, s), 24, CarStyle.ACCENT);
        cardMeta.setPadding(0, 4, 0, 12);
        card.addView(cardMeta);
        String body = RoadGuide.clean(p.extract, RoadGuide.CARD_CHARS);
        if (!body.isEmpty()) {
            TextView ex = CarStyle.text(c, body, 23, CarStyle.TEXT_DIM);
            ex.setLineSpacing(0, 1.15f);
            ex.setMaxLines(wide ? 8 : 6);
            ex.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(ex);
        }
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, 18, 0, 0);
        if (!body.isEmpty()) {
            TextView tell = CarStyle.pill(c, Str.get(R.string.hql_guide_tell));
            tell.setOnClickListener(v -> RoadGuide.tell(p));
            buttons.addView(tell);
        }
        TextView go = CarStyle.pill(c, Str.get(R.string.hql_guide_go));
        go.setBackground(CarStyle.accent(28));
        go.setTextColor(CarStyle.ON_ACCENT);
        go.setOnClickListener(v -> NavApps.go(c, p.lat, p.lon));
        buttons.addView(go);
        card.addView(buttons);
        return card;
    }

    private View row(Context c, RoadGuide.Place p, RoadGuide.State s) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(14, 12, 14, 12);
        r.setBackground(CarStyle.round(CarStyle.ITEM_BG, 18));
        r.setMinimumHeight(CarStyle.touch(96));
        ImageView img = new ImageView(c);
        img.setBackground(CarStyle.round(CarStyle.CARD, 14));
        img.setClipToOutline(true);
        r.addView(img, new LinearLayout.LayoutParams(84, 84));
        thumb(c, img, p);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(18, 0, 0, 0);
        TextView name = CarStyle.text(c, p.title, 24, CarStyle.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView m = CarStyle.text(c, meta(p, s), 20, CarStyle.TEXT_DIM);
        m.setSingleLine(true);
        texts.addView(name);
        texts.addView(m);
        rowMetas.add(m);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(v -> {
            RoadGuide.select(p);
            if (!live) onPick(p, s);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10;
        r.setLayoutParams(lp);
        return r;
    }

    /** Sin la guía en marcha (pruebas): el mismo estado, ya callado. */
    private static RoadGuide.State copyQuiet(RoadGuide.State s) {
        RoadGuide.State n = new RoadGuide.State();
        n.status = s.status;
        n.moving = s.moving;
        n.voice = s.voice;
        n.lat = s.lat;
        n.lon = s.lon;
        n.heading = s.heading;
        n.current = s.current;
        n.list.addAll(s.list);
        return n;
    }

    /** Sin la guía en marcha (pruebas): lo tocado pasa a la tarjeta al momento. */
    private void onPick(RoadGuide.Place p, RoadGuide.State s) {
        RoadGuide.State n = new RoadGuide.State();
        n.status = s.status;
        n.moving = s.moving;
        n.voice = s.voice;
        n.lat = s.lat;
        n.lon = s.lon;
        n.heading = s.heading;
        n.current = p;
        if (s.current != null) n.list.add(s.current);
        for (RoadGuide.Place q : s.list) if (q != p) n.list.add(q);
        render(n);
    }

    /** La foto de Wikimedia (pide identificarse) o, sin foto, el icono de la guía. */
    private static void thumb(Context c, ImageView img, RoadGuide.Place p) {
        if (p.thumb.isEmpty()) {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setPadding(18, 18, 18, 18);
            img.setImageResource(R.drawable.hql_ic_guide);
            img.setImageTintList(ColorStateList.valueOf(CarStyle.TEXT_DIM));
            return;
        }
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setPadding(0, 0, 0, 0);
        img.setImageTintList(null);
        GlideUrl url = new GlideUrl(p.thumb, new LazyHeaders.Builder().addHeader("User-Agent", Http.USER_AGENT).build());
        Glide.with(c.getApplicationContext()).load(url).centerCrop().error(R.drawable.hql_ic_guide).into(img);
    }
}
