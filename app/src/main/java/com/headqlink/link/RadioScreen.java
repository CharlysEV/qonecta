package com.headqlink.link;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.andrerinas.openheadunit.R;
import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Radio por internet: emisoras populares del país (directorio abierto radio-browser.info; el país, automático o el
 * elegido en «Cambiar país», ver {@link #country}) o la
 * lista M3U del usuario (Config.radioSource, URL o archivo), y «Mi música» (las canciones del móvil, MusicLibrary). Lo
 * que suena sigue al salir de aquí (RadioPlayer); arriba, la emisora o canción actual con lo que emite y los controles.
 */
final class RadioScreen implements CarScreen {
    /** Servidores del directorio; "all" reparte entre ellos y los demás son el respaldo. */
    private static final String[] DIRECTORY = {"all.api.radio-browser.info", "de1.api.radio-browser.info",
            "fi1.api.radio-browser.info", "nl1.api.radio-browser.info"};
    private static final int COLUMNS = 4;

    private Host host;
    private final List<RadioPlayer.Station> shown = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private FrameLayout listArea;
    private ImageView npLogo;
    private TextView npName;
    private TextView npTitle;
    private ImageView npToggle;
    private volatile boolean destroyed;
    private int loadSeq;
    /** Las listas M3U del usuario (Config.m3uLists): la pestaña 1 + i es la lista i. */
    private String[][] lists = new String[0][];
    private final Runnable onChange = this::renderNowPlaying;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(nowPlaying(c));
        // Pestañas: las populares y cada lista del usuario (o «Mi lista» si no tiene ninguna).
        Config cfg = new Config(c);
        lists = cfg.m3uLists(Config.RADIO);
        boolean hasList = lists.length > 0;
        favs = Favorites.load(cfg, Favorites.RADIO);
        // ★ (favoritos), Populares, cada lista (o «Mi lista» si no hay ninguna) y «Mi música»: la pestaña i es la fuente
        // i - 1; la última, MUSIC.
        String[] tabs = new String[3 + Math.max(1, lists.length)];
        tabs[0] = "★";
        tabs[1] = Str.get(R.string.hql_radio_popular);
        if (hasList) for (int i = 0; i < lists.length; i++) tabs[2 + i] = lists[i][0];
        else tabs[2] = Str.get(R.string.hql_radio_my_list);
        int musicTab = tabs.length - 1;
        tabs[musicTab] = "♪ " + Str.get(R.string.hql_music_mine);
        RadioPlayer.Station playing = RadioPlayer.current();
        int first = playing != null && playing.local ? musicTab : hasList ? 2 + cfg.m3uSelected(Config.RADIO) : 1;
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(CarStyle.tabs(c, tabs, first, i -> load(i == musicTab ? MUSIC : i - 1)));
        col.addView(hs);
        listArea = new FrameLayout(c);
        col.addView(listArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        RadioPlayer.addListener(onChange);
        renderNowPlaying();
        load(first == musicTab ? MUSIC : first - 1);
        return col;
    }

    /** La fuente «Mi música». */
    private static final int MUSIC = -2;

    @Override
    public void destroy() {
        destroyed = true;
        RadioPlayer.removeListener(onChange);
    }

    private View nowPlaying(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(24, 18, 24, 18);
        card.setBackground(CarStyle.round(CarStyle.CARD, 28));
        npLogo = new ImageView(c);
        npLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        npLogo.setBackground(CarStyle.round(CarStyle.ITEM_BG, 16));
        npLogo.setPadding(8, 8, 8, 8);
        card.addView(npLogo, new LinearLayout.LayoutParams(96, 96));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(22, 0, 12, 0);
        npName = CarStyle.text(c, "", 30, CarStyle.TEXT);
        npName.setSingleLine(true);
        npTitle = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        npTitle.setSingleLine(true);
        texts.addView(npName);
        texts.addView(npTitle);
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Iconos de Material, no caracteres: ⏸ y similares salen como emoji de color.
        ImageView prev = control(c, R.drawable.hql_ic_prev);
        npToggle = control(c, R.drawable.hql_ic_play);
        ImageView next = control(c, R.drawable.hql_ic_next);
        ImageView stop = control(c, R.drawable.hql_ic_stop);
        for (ImageView t : new ImageView[]{prev, npToggle, next, stop}) card.addView(t);
        prev.setOnClickListener(v -> RadioPlayer.step(c, -1));
        next.setOnClickListener(v -> RadioPlayer.step(c, 1));
        npToggle.setOnClickListener(v -> RadioPlayer.toggle());
        stop.setOnClickListener(v -> RadioPlayer.stopByUser());
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(24, 18, 24, 0);
        wrap.addView(card);
        return wrap;
    }

    private static ImageView control(Context c, int icon) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(CarStyle.TEXT));
        b.setBackground(CarStyle.round(CarStyle.PILL_BG, 30));
        b.setPadding(20, 20, 20, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CarStyle.touch(84), CarStyle.touch(72));
        lp.leftMargin = 10;
        b.setLayoutParams(lp);
        return b;
    }

    private void renderNowPlaying() {
        if (destroyed || npName == null) return;
        RadioPlayer.Station s = RadioPlayer.current();
        if (s == null) {
            npName.setText(Str.get(R.string.hql_radio));
            npTitle.setText(Str.get(R.string.hql_radio_pick));
            npLogo.setImageResource(R.drawable.hql_ic_radio);
            npToggle.setImageResource(R.drawable.hql_ic_play);
            return;
        }
        npName.setText(s.name);
        String sub = !RadioPlayer.error.isEmpty() ? RadioPlayer.error
                : RadioPlayer.isBuffering() ? Str.get(R.string.hql_connecting)
                : s.local ? (RadioPlayer.isPlaying() ? MusicLibrary.line(s) : Str.get(R.string.hql_paused))
                : !RadioPlayer.nowTitle.isEmpty() ? RadioPlayer.nowTitle
                : RadioPlayer.isPlaying() ? Str.get(R.string.hql_radio_live) : Str.get(R.string.hql_paused);
        npTitle.setText(sub);
        npToggle.setImageResource(RadioPlayer.isPlaying() ? R.drawable.hql_ic_pause : R.drawable.hql_ic_play);
        int icon = s.local ? R.drawable.hql_ic_music : R.drawable.hql_ic_radio;
        if (s.logo != null && !s.logo.isEmpty()) {
            Glide.with(host.context().getApplicationContext()).load(s.logo).error(icon).into(npLogo);
        } else {
            npLogo.setImageResource(icon);
        }
    }

    private JSONArray favs = new JSONArray();
    private int currentSource;

    /** -1: favoritos; 0: populares del directorio; 1 + i: la lista M3U i del usuario (queda elegida). */
    private void load(int source) {
        Context c = host.context();
        int seq = ++loadSeq;
        currentSource = source;
        if (source == MUSIC) {
            loadMusic(c, seq);
            return;
        }
        if (source < 0) {
            // Las de ahora: en el móvil se pueden haber ordenado, renombrado o quitado (RadioFavsEditor).
            favs = Favorites.load(new Config(c), Favorites.RADIO);
            shown.clear();
            // Con el usuario de su lista en las que lo tienen (guardadas sin él).
            shown.addAll(RadioLibrary.stations(favs, RadioLibrary.logins(c)));
            if (shown.isEmpty()) showMessage(Str.get(R.string.hql_fav_none));
            else showGrid(c);
            return;
        }
        if (source >= 1 && lists.length == 0) {
            showMessage(Str.get(R.string.hql_radio_setup_list));
            return;
        }
        String src = source >= 1 ? lists[source - 1][1] : "";
        String loginId = source >= 1 ? Config.m3uLoginId(lists[source - 1]) : "";
        if (source >= 1) new Config(c).setM3uSelected(Config.RADIO, source - 1);
        String cc = source == 0 ? country(c) : "";
        showMessage(source == 0 ? Str.get(R.string.hql_radio_loading_popular) : Str.get(R.string.hql_radio_loading_list));
        if (source == 0) addCountryBar(c, cc);
        new Thread(() -> {
            List<RadioPlayer.Station> list = new ArrayList<>();
            String error = null;
            String converted = null;
            M3uSource.Login login = M3uSource.Login.NONE;
            try {
                if (source == 0) {
                    list = popular(cc);
                } else {
                    // La lista del usuario, comprobada (M3uSource): una página web, un mensaje claro y no emisoras basura.
                    M3uSource.Loaded got = M3uSource.load(c, src, loginId);
                    list = fromM3u(got, loginId);
                    converted = got.converted;
                    login = got.login;
                    // Las recientes guardadas antes con su usuario dentro de la URL, ya sin él.
                    if (!login.isEmpty()) RadioLibrary.hideLoginInRecents(c, login, loginId);
                }
            } catch (Exception e) {
                if (source == 0) {
                    error = Str.get(R.string.hql_load_failed, e.getMessage());
                    L.w("radio: no se pudo cargar la lista: " + e);
                } else {
                    M3uSource.ListError le = M3uSource.ListError.of(e, null);
                    error = le.text(R.string.hql_load_failed);
                    L.w("radio: no se pudo cargar la lista: " + le);
                }
            }
            List<RadioPlayer.Station> fl = list;
            String fe = error;
            String fc = converted;
            M3uSource.Login fLogin = login;
            host.post(() -> {
                if (destroyed || seq != loadSeq) return;
                // Los favoritos guardados antes con su usuario dentro de la URL, ya sin él (como la TV).
                favs = Favorites.load(new Config(c), Favorites.RADIO);
                if (M3uSource.hideInFavorites(favs, fLogin, loginId) > 0) Favorites.save(new Config(c), Favorites.RADIO, favs);
                if (fl.isEmpty()) {
                    showMessage(fe != null ? fe : Str.get(R.string.hql_radio_none));
                } else {
                    shown.clear();
                    shown.addAll(fl);
                    showGrid(c);
                    if (fc != null) {
                        // Página conocida (iprd): se guarda su lista de verdad, que también se ve ya en el móvil, y se avisa.
                        Config cfg = new Config(c);
                        cfg.replaceM3uSource(Config.RADIO, src, fc);
                        lists = cfg.m3uLists(Config.RADIO);
                        L.i("radio: la lista " + source + " era una página conocida: cambiada por su lista");
                        TextView notice = CarStyle.text(c, Str.get(R.string.hql_list_converted, fc), 20, CarStyle.TEXT_DIM);
                        notice.setSingleLine(true);
                        notice.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                        notice.setPadding(24, 6, 24, 2);
                        wrapWithBar(c, notice);
                    }
                }
                // Populares: arriba, de qué país son y «Cambiar país» (también sin emisoras, para poder cambiarlo).
                if (source == 0) addCountryBar(c, cc);
            });
        }, "radio-load").start();
    }

    // ---------------------------------------------------------------- país de las populares

    /** El país de las populares: el elegido a mano en «Cambiar país» o, si no, el automático. */
    static String country(Context c) {
        String manual = new Config(c).radioCountry();
        return manual.isEmpty() ? autoCountry(c) : manual;
    }

    /**
     * El país automático: el de la región del sistema (no cambia al poner la app en otro idioma, que puede venir sin
     * país), el del idioma de la app, el de la red móvil (como los cargadores) y, si nada lo dice, España.
     */
    static String autoCountry(Context c) {
        android.content.res.Configuration sys = android.content.res.Resources.getSystem().getConfiguration();
        @SuppressWarnings("deprecation")
        Locale system = android.os.Build.VERSION.SDK_INT >= 24 ? sys.getLocales().get(0) : sys.locale;
        return pickCountry(system == null ? null : system.getCountry(), Locale.getDefault().getCountry(), Country.current(c));
    }

    /** Puro: el primero que sea un código de país de dos letras (en mayúsculas); si ninguno, España. */
    static String pickCountry(String... candidates) {
        for (String s : candidates) {
            if (s != null && s.length() == 2 && Character.isLetter(s.charAt(0)) && Character.isLetter(s.charAt(1))) {
                return s.toUpperCase(Locale.US);
            }
        }
        return "ES";
    }

    /** Puro: la bandera (emoji) de un código de país de dos letras. */
    static String flag(String iso) {
        if (iso == null || iso.length() != 2) return "";
        String u = iso.toUpperCase(Locale.US);
        return new String(Character.toChars(0x1F1E6 + u.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + u.charAt(1) - 'A'));
    }

    /** Nombre del país en el idioma de la app («Alemania», «Deutschland»…). */
    static String countryName(String iso) {
        String n = new Locale("", iso).getDisplayCountry(Locale.getDefault());
        return n == null || n.isEmpty() ? iso : n;
    }

    /** Encima de lo que haya en listArea: «Las más escuchadas · 🇩🇪 Alemania» y «Cambiar país». */
    private void addCountryBar(Context c, String cc) {
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(24, 4, 24, 4);
        TextView what = CarStyle.text(c, Str.get(R.string.hql_radio_country_line, flag(cc) + " " + countryName(cc)), 22,
                CarStyle.TEXT_DIM);
        what.setSingleLine(true);
        bar.addView(what, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView change = CarStyle.pill(c, Str.get(R.string.hql_radio_country_change));
        change.setOnClickListener(v -> showCountries(c));
        bar.addView(change);
        wrapWithBar(c, bar);
    }

    /** Pone una barra encima de lo que haya en listArea (la cuadrícula o el mensaje). */
    private void wrapWithBar(Context c, View bar) {
        View content = listArea.getChildAt(0);
        listArea.removeAllViews();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(bar);
        if (content != null) col.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listArea.addView(col, CarStyle.match());
    }

    /** «Cambiar país»: «Automático» (con el país que saldría) y todos los países de la A a la Z; el elegido, marcado. */
    private void showCountries(Context c) {
        loadSeq++;
        String manual = new Config(c).radioCountry();
        String auto = autoCountry(c);
        List<String[]> items = new ArrayList<>();
        items.add(new String[]{"", Str.get(R.string.hql_radio_country_auto, flag(auto) + " " + countryName(auto))});
        List<String[]> all = new ArrayList<>();
        for (String iso : Locale.getISOCountries()) all.add(new String[]{iso, flag(iso) + " " + countryName(iso)});
        java.text.Collator col = java.text.Collator.getInstance(Locale.getDefault());
        java.util.Collections.sort(all, (a, b) -> col.compare(countryName(a[0]), countryName(b[0])));
        items.addAll(all);
        RecyclerView rv = new RecyclerView(c);
        rv.setPadding(18, 6, 18, 6);
        int cols = CarStyle.gridColumns(COLUMNS, host.width() - 36, CarStyle.MIN_CELL_SCREEN_PX);
        rv.setLayoutManager(new GridLayoutManager(c, cols));
        int cellW = (host.width() - 36) / cols;
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                TextView t = CarStyle.text(parent.getContext(), "", 24, CarStyle.TEXT);
                t.setMaxLines(2);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(20, 10, 14, 10);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(cellW - 12, 88);
                lp.setMargins(6, 6, 6, 6);
                t.setLayoutParams(lp);
                return new RecyclerView.ViewHolder(t) {
                };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder vh, int pos) {
                String[] it = items.get(pos);
                boolean on = it[0].equals(manual);
                TextView t = (TextView) vh.itemView;
                t.setText(on ? "✓ " + it[1] : it[1]);
                t.setTextColor(on ? CarStyle.ACCENT : CarStyle.TEXT);
                t.setBackground(CarStyle.round(on ? CarStyle.ACCENT_BG : CarStyle.ITEM_BG, 16));
                t.setOnClickListener(v -> {
                    new Config(c).setRadioCountry(it[0]);
                    L.i("radio: país de las populares " + (it[0].isEmpty() ? "automático (" + autoCountry(c) + ")" : it[0]));
                    load(0);
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        });
        // El elegido, a la vista (Automático ya está arriba).
        int sel = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i)[0].equals(manual)) sel = i;
        }
        rv.scrollToPosition(sel);
        listArea.removeAllViews();
        listArea.addView(rv, CarStyle.match());
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(24, 4, 24, 4);
        TextView title = CarStyle.text(c, Str.get(R.string.hql_radio_country_title), 22, CarStyle.TEXT_DIM);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView back = CarStyle.pill(c, Str.get(R.string.hql_back));
        back.setOnClickListener(v -> load(0));
        bar.addView(back);
        wrapWithBar(c, bar);
    }

    /** «Mi música»: las canciones del móvil, con «Aleatorio» arriba; sin permiso o sin canciones, lo dice. */
    private void loadMusic(Context c, int seq) {
        if (!MusicLibrary.granted(c)) {
            showMessage(Str.get(R.string.hql_music_perm));
            return;
        }
        showMessage(Str.get(R.string.hql_music_loading));
        new Thread(() -> {
            List<RadioPlayer.Station> songs = MusicLibrary.query(c);
            host.post(() -> {
                if (destroyed || seq != loadSeq) return;
                if (songs.isEmpty()) {
                    showMessage(Str.get(R.string.hql_music_none));
                    return;
                }
                shown.clear();
                shown.addAll(songs);
                showGrid(c);
                // Arriba, «Aleatorio»: toda la música en otro orden.
                LinearLayout bar = new LinearLayout(c);
                bar.setGravity(Gravity.CENTER_VERTICAL);
                bar.setPadding(24, 4, 24, 4);
                TextView count = CarStyle.text(c, Str.plural(R.plurals.hql_music_songs, songs.size()), 22, CarStyle.TEXT_DIM);
                bar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView shuffle = CarStyle.pill(c, Str.get(R.string.hql_music_shuffle));
                shuffle.setOnClickListener(v -> {
                    List<RadioPlayer.Station> mix = new ArrayList<>(songs);
                    java.util.Collections.shuffle(mix);
                    RadioPlayer.play(c, mix, 0);
                });
                bar.addView(shuffle);
                View grid = listArea.getChildAt(0);
                listArea.removeAllViews();
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.addView(bar);
                col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                listArea.addView(col, CarStyle.match());
            });
        }, "music-load").start();
    }

    private void showMessage(String s) {
        listArea.removeAllViews();
        listArea.addView(CarStyle.message(host.context(), s), CarStyle.match());
    }

    /** Las 150 emisoras más escuchadas del país cc (directorio radio-browser.info), sin las que no funcionan. */
    private static List<RadioPlayer.Station> popular(String cc) throws Exception {
        String q = "/json/stations/search?countrycode=" + URLEncoder.encode(cc, "UTF-8")
                + "&order=clickcount&reverse=true&hidebroken=true&limit=150";
        Exception last = null;
        for (String host : DIRECTORY) {
            try {
                JSONArray a = new JSONArray(Http.get("https://" + host + q));
                List<RadioPlayer.Station> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    RadioPlayer.Station s = new RadioPlayer.Station();
                    s.name = o.optString("name").trim();
                    s.url = o.optString("url_resolved", o.optString("url"));
                    s.logo = o.optString("favicon");
                    s.group = o.optString("tags");
                    if (!s.name.isEmpty() && !s.url.isEmpty()) out.add(s);
                }
                return out;
            } catch (Exception e) {
                last = e;
                L.w("radio: directorio " + host + ": " + e.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("sin directorio");
    }

    /**
     * Las emisoras de una lista M3U ya comprobada (M3uSource.load), con el usuario de la lista (loginId) si lo tiene: en
     * Xtream va dentro de cada URL y, con HTTP Basic, como cabecera a su servidor. La JSON de TDTChannels es de TV, aquí no
     * vale.
     */
    private static List<RadioPlayer.Station> fromM3u(M3uSource.Loaded got, String loginId) throws Exception {
        if (got.text.trim().startsWith("{")) throw M3uSource.ListError.of(M3uSource.ListError.NOT_LIST);
        boolean withLogin = got.login != null && !got.login.isEmpty() && loginId != null && !loginId.isEmpty();
        List<RadioPlayer.Station> out = new ArrayList<>();
        for (IptvScreen.Channel ch : IptvScreen.parse(got.text)) {
            RadioPlayer.Station s = new RadioPlayer.Station();
            s.name = ch.name;
            s.url = ch.url;
            s.logo = ch.logo;
            s.group = ch.group;
            if (withLogin) {
                s.login = got.login;
                s.loginId = loginId;
                s.authHost = got.authHeader != null ? got.authHost : null;
            }
            out.add(s);
        }
        return out;
    }

    private void showGrid(Context c) {
        // Buscar y ordenar (no en «Mi música»): se parte de la lista entera, con el orden guardado y sin búsqueda.
        final boolean searchable = currentSource != MUSIC;
        if (searchable) {
            base = new ArrayList<>(shown);
            index = null;
            query.setLength(0);
            sortMode = new Config(c).radioSort();
            hasGroups = false;
            for (RadioPlayer.Station st : base) {
                if (st.group != null && !st.group.trim().isEmpty()) {
                    hasGroups = true;
                    break;
                }
            }
            if (!hasGroups && sortMode == RadioFilter.GROUPED) sortMode = RadioFilter.ORIGINAL;
        }
        RecyclerView rv = new RecyclerView(c);
        rv.setPadding(18, 6, 18, 6);
        // En una pantalla pequeña (T03), menos columnas: celdas más anchas para el texto más grande.
        int cols = CarStyle.gridColumns(COLUMNS, host.width() - 36, CarStyle.MIN_CELL_SCREEN_PX);
        rv.setLayoutManager(new GridLayoutManager(c, cols));
        int cellW = (host.width() - 36) / cols;
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                LinearLayout item = new LinearLayout(parent.getContext());
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(14, 10, 14, 10);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(cellW - 12, 104);
                lp.setMargins(6, 6, 6, 6);
                item.setLayoutParams(lp);
                item.setBackground(CarStyle.round(CarStyle.ITEM_BG, 16));
                ImageView logo = new ImageView(parent.getContext());
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                item.addView(logo, new LinearLayout.LayoutParams(76, 76));
                TextView name = CarStyle.text(parent.getContext(), "", 24, CarStyle.TEXT);
                name.setMaxLines(2);
                name.setPadding(16, 0, 0, 0);
                item.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return new RecyclerView.ViewHolder(item) {
                };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder vh, int pos) {
                RadioPlayer.Station s = shown.get(pos);
                LinearLayout item = (LinearLayout) vh.itemView;
                ImageView logo = (ImageView) item.getChildAt(0);
                String name = Favorites.contains(favs, RadioLibrary.storedUrl(s)) ? "★ " + s.name : s.name;
                // Por grupos, el grupo debajo del nombre.
                if (searchable && sortMode == RadioFilter.GROUPED && s.group != null && !s.group.trim().isEmpty()) {
                    name = name + "\n" + s.group.trim();
                }
                // Una canción: el título y, debajo, el artista y la duración.
                ((TextView) item.getChildAt(1)).setText(s.local ? name + "\n" + MusicLibrary.line(s) : name);
                int icon = s.local ? R.drawable.hql_ic_music : R.drawable.hql_ic_radio;
                if (s.logo != null && !s.logo.isEmpty()) {
                    Glide.with(c.getApplicationContext()).load(s.logo).error(icon).into(logo);
                } else {
                    logo.setImageResource(icon);
                }
                item.setOnClickListener(v -> {
                    int i = vh.getBindingAdapterPosition();
                    if (i >= 0) RadioPlayer.play(c, shown, i);
                });
                // Mantener pulsado: entra o sale de favoritos.
                item.setOnLongClickListener(v -> {
                    int i = vh.getBindingAdapterPosition();
                    if (i < 0) return true;
                    RadioPlayer.Station st = shown.get(i);
                    if (st.local) return true; // las canciones no van a favoritos de la radio
                    // Sobre las guardadas de ahora (no las de al abrir: el móvil puede haberlas cambiado).
                    favs = Favorites.load(new Config(c), Favorites.RADIO);
                    boolean in = Favorites.toggle(favs, RadioLibrary.item(st));
                    Favorites.save(new Config(c), Favorites.RADIO, favs);
                    RadioBrowserService.changed(RadioLibrary.FAVS);
                    L.i("radio: " + (in ? "favorita añadida" : "favorita quitada") + " (" + favs.length() + ")");
                    if (currentSource < 0) load(-1);
                    else if (adapter != null) adapter.notifyDataSetChanged();
                    return true;
                });
            }

            @Override
            public int getItemCount() {
                return shown.size();
            }
        };
        rv.setAdapter(adapter);
        listArea.removeAllViews();
        listArea.addView(searchable ? searchColumn(c, rv) : rv, CarStyle.match());
        if (searchable && sortMode != RadioFilter.ORIGINAL) refresh();
    }

    // ---------------------------------------------------------------- buscar y ordenar

    /** La lista entera de la pestaña (shown es lo que se ve: filtrada y ordenada). */
    private List<RadioPlayer.Station> base = new ArrayList<>();
    private RadioFilter.Index index;
    private List<RadioPlayer.Station> indexBase;
    private final StringBuilder query = new StringBuilder();
    private int sortMode;
    private boolean hasGroups;
    /** Sube con cada cambio: el resultado de uno viejo (lista grande, en otro hilo) no pisa el nuevo. */
    private int viewSeq;
    private TextView queryBox;
    private TextView clearPill;
    private TextView sortPill;
    private TextView countLine;
    private CarKeyboard keyboard;

    /** Encima de la cuadrícula, la caja de búsqueda, «Borrar» y el orden; debajo, el teclado del panel (oculto hasta tocar la caja). */
    private View searchColumn(Context c, RecyclerView rv) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(24, 4, 24, 4);
        queryBox = CarStyle.text(c, "", 24, CarStyle.TEXT);
        queryBox.setHint(Str.get(R.string.hql_radio_search_hint));
        queryBox.setHintTextColor(CarStyle.TEXT_DIM);
        queryBox.setSingleLine(true);
        queryBox.setEllipsize(android.text.TextUtils.TruncateAt.START);
        queryBox.setGravity(Gravity.CENTER_VERTICAL);
        queryBox.setPadding(26, 14, 26, 14);
        queryBox.setBackground(CarStyle.round(CarStyle.ITEM_BG, 28));
        CarStyle.minTouch(queryBox);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qlp.rightMargin = 10;
        bar.addView(queryBox, qlp);
        clearPill = CarStyle.pill(c, "✕ " + Str.get(R.string.hql_radio_search_clear));
        clearPill.setVisibility(View.GONE);
        bar.addView(clearPill);
        sortPill = CarStyle.pill(c, sortLabel());
        bar.addView(sortPill);
        col.addView(bar);
        countLine = CarStyle.text(c, "", 20, CarStyle.TEXT_DIM);
        countLine.setSingleLine(true);
        countLine.setPadding(30, 2, 24, 2);
        countLine.setVisibility(View.GONE);
        col.addView(countLine);
        col.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        CarKeyboard kb = new CarKeyboard(c, new CarKeyboard.Listener() {
            @Override
            public void onText(String t) {
                query.append(t);
                queryChanged();
            }

            @Override
            public void onBackspace() {
                if (query.length() > 0) query.setLength(query.length() - 1);
                queryChanged();
            }

            @Override
            public void onEnter() {
                // «Ir»: ya se filtra al escribir; solo se quita el teclado para ver más emisoras.
                keyboard.setVisibility(View.GONE);
            }

            @Override
            public void onHide() {
                keyboard.setVisibility(View.GONE);
            }
        });
        keyboard = kb;
        kb.setVisibility(View.GONE);
        col.addView(kb);
        queryBox.setOnClickListener(v -> {
            kb.setVisibility(View.VISIBLE);
            kb.showText(query, Str.get(R.string.hql_radio_search_hint));
        });
        clearPill.setOnClickListener(v -> {
            query.setLength(0);
            queryChanged();
        });
        sortPill.setOnClickListener(v -> {
            sortMode = RadioFilter.next(sortMode, hasGroups);
            new Config(c).setRadioSort(sortMode);
            sortPill.setText(sortLabel());
            refresh();
        });
        return col;
    }

    private String sortLabel() {
        return Str.get(sortMode == RadioFilter.AZ ? R.string.hql_radio_sort_az
                : sortMode == RadioFilter.GROUPED ? R.string.hql_radio_sort_group : R.string.hql_radio_sort_original);
    }

    /** Cambió lo escrito: se ve en la caja y sobre el teclado, y se filtra ya (a cada letra). */
    private void queryChanged() {
        if (queryBox == null) return;
        queryBox.setText(query);
        clearPill.setVisibility(query.length() > 0 ? View.VISIBLE : View.GONE);
        if (keyboard != null) keyboard.showText(query, Str.get(R.string.hql_radio_search_hint));
        refresh();
    }

    /**
     * Pone en shown las emisoras que encajan con la búsqueda, en el orden elegido. Las listas grandes (miles de emisoras)
     * se preparan y filtran en otro hilo; las pequeñas, al momento.
     */
    private void refresh() {
        int seq = ++viewSeq;
        int mode = sortMode;
        String q = query.toString();
        List<RadioPlayer.Station> src = base;
        if (src.size() < 1000) {
            applyView(seq, ensureIndex(src).view(mode, q), q);
            return;
        }
        new Thread(() -> {
            List<RadioPlayer.Station> v = ensureIndex(src).view(mode, q);
            host.post(() -> applyView(seq, v, q));
        }, "radio-filter").start();
    }

    private synchronized RadioFilter.Index ensureIndex(List<RadioPlayer.Station> src) {
        if (index == null || indexBase != src) {
            index = new RadioFilter.Index(src);
            indexBase = src;
        }
        return index;
    }

    private void applyView(int seq, List<RadioPlayer.Station> v, String q) {
        if (destroyed || seq != viewSeq || adapter == null) return;
        shown.clear();
        shown.addAll(v);
        adapter.notifyDataSetChanged();
        if (countLine == null) return;
        boolean filtering = RadioFilter.words(q).length > 0;
        if (filtering && v.isEmpty()) countLine.setText(Str.get(R.string.hql_radio_search_none, q.trim()));
        else countLine.setText(Str.get(R.string.hql_radio_search_count, v.size(), base.size()));
        countLine.setVisibility(filtering ? View.VISIBLE : View.GONE);
    }
}
