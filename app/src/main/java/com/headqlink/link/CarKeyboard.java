package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Teclado en la propia pantalla del coche. El teclado de Android saldría en el móvil (el
 * VirtualDisplay no tiene método de entrada), así que la web, las búsquedas y las URL usan este.
 *
 * Cada tecla se ilumina al pulsarla (y un momento después, para que se vea aunque el toque sea muy corto) y, si quien lo
 * usa llama a showText o showField, encima de las teclas va lo que se está escribiendo: se ve sin apartar la vista del
 * teclado (en una contraseña, puntos y la última letra un momento).
 *
 * Tres capas en las filas de letras (los números arriba y la fila de abajo no cambian): letras, «#+=» con todos los
 * símbolos del teclado de un ordenador (cualquier contraseña se puede escribir; el 26-10 un usuario no podía entrar en
 * Google por una «#») y «äé» con acentos y diéresis («Straße», «Köln», «São Paulo» en la búsqueda de la ruta). La capa de
 * símbolos se queda hasta volver a «abc»; la de acentos vuelve sola a las letras tras uno. ⇧ pone en mayúscula la
 * siguiente letra; con dos toques seguidos, fijas (⇪). ⌫ mantenido sigue borrando.
 */
final class CarKeyboard extends LinearLayout {
    interface Listener {
        void onText(String s);

        void onBackspace();

        void onEnter();

        void onHide();
    }

    static final int LETTERS = 0;
    static final int SYMBOLS = 1;
    static final int ACCENTS = 2;

    private static final String DIGITS = "1234567890";
    /** Las filas que cambian con la capa (LETTERS, SYMBOLS, ACCENTS). */
    static final String[][] LAYERS = {
            {"qwertyuiop", "asdfghjklñ", "zxcvbnm"},
            {"!#$%&*()=+", "?:;,'\"<>€°", "[]{}\\|^~`"},
            {"äöüßéèêëáà", "âãçíìîïóòô", "õúùûœ¿¡"},
    };
    /** La fila de abajo, siempre: lo que más se escribe en direcciones y correos. */
    static final String BOTTOM = ".@-_/";
    /** El resto de símbolos de un teclado de ordenador también están en la fila de abajo (BOTTOM). */
    private static final String LAYER_SYMBOLS = "#+=";
    private static final String LAYER_ACCENTS = "äé";
    private static final String LAYER_LETTERS = "abc";

    /** Cuánto se queda iluminada una tecla tras pulsarla. */
    private static final long FLASH_MS = 160;
    /** Dos toques en ⇧ dentro de esto: mayúsculas fijas. */
    private static final long CAPS_LOCK_MS = 450;
    /** ⌫ mantenido: empieza a repetir tras esto y luego borra cada REPEAT_MS. */
    private static final long REPEAT_DELAY_MS = 450;
    private static final long REPEAT_MS = 70;
    /** Contraseña: cuánto se ve la última letra escrita antes de pasar a punto. */
    private static final long REVEAL_MS = 1200;
    private static final int KEY_LIT = 0xFF00E5C7;
    private static final char DOT = '•';

    private final Listener listener;
    private int layer = LETTERS;
    private boolean shift;
    private boolean capsLock;
    private long lastShiftMs;
    /** Las teclas que cambian con la capa y con ⇧ (las tres filas del medio). */
    private final java.util.List<TextView> layerKeys = new java.util.ArrayList<>();
    private final LinearLayout[] layerRows = new LinearLayout[3];
    private TextView shiftKey;
    private TextView symbolsKey;
    private TextView accentsKey;
    /** Lo escrito, encima de las teclas (oculto hasta el primer showText). */
    private final TextView echo;
    /** Contraseña: vuelve a poner el punto en la última letra. */
    private Runnable hideLast;

    CarKeyboard(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setOrientation(VERTICAL);
        // Con los colores del tema (día o noche): CarUi rehace las vistas al cambiar.
        setBackgroundColor(CarTheme.night() ? 0xFF15181C : CarStyle.BG);
        setPadding(10, 10, 10, 10);
        echo = CarStyle.text(c, "", 30, CarStyle.TEXT);
        echo.setSingleLine(true);
        echo.setEllipsize(android.text.TextUtils.TruncateAt.START);
        echo.setGravity(Gravity.CENTER_VERTICAL);
        echo.setPadding(22, 0, 22, 0);
        echo.setVisibility(GONE);
        LayoutParams elp = new LayoutParams(LayoutParams.MATCH_PARENT, 62);
        elp.setMargins(4, 0, 4, 6);
        addView(echo, elp);

        LinearLayout digits = row();
        for (char ch : DIGITS.toCharArray()) {
            String s = String.valueOf(ch);
            digits.addView(key(s, 1f, () -> listener.onText(s)));
        }
        addView(digits);
        for (int r = 0; r < layerRows.length; r++) {
            layerRows[r] = row();
            addView(layerRows[r]);
        }
        shiftKey = key("⇧", 1.5f, this::onShift);

        LinearLayout bottom = row();
        symbolsKey = key(LAYER_SYMBOLS, 1.1f, () -> setLayer(layer == SYMBOLS ? LETTERS : SYMBOLS));
        accentsKey = key(LAYER_ACCENTS, 1.1f, () -> setLayer(layer == ACCENTS ? LETTERS : ACCENTS));
        bottom.addView(symbolsKey);
        bottom.addView(accentsKey);
        for (char ch : BOTTOM.toCharArray()) {
            String s = String.valueOf(ch);
            bottom.addView(key(s, 0.7f, () -> listener.onText(s)));
        }
        bottom.addView(key(".com", 1f, () -> listener.onText(".com")));
        bottom.addView(key(Str.get(R.string.hql_key_space), 2.2f, () -> listener.onText(" ")));
        bottom.addView(key(Str.get(R.string.hql_go), 1.2f, listener::onEnter));
        bottom.addView(key(Str.get(R.string.hql_hide), 1.4f, listener::onHide));
        addView(bottom);
        buildRows();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 72));
        return row;
    }

    /** Las tres filas del medio para la capa de ahora; ⇧ en la última (no en símbolos) y ⌫ al final. */
    private void buildRows() {
        layerKeys.clear();
        String[] rows = LAYERS[layer];
        for (int r = 0; r < layerRows.length; r++) {
            LinearLayout row = layerRows[r];
            row.removeAllViews();
            boolean last = r == layerRows.length - 1;
            if (last && layer != SYMBOLS) row.addView(shiftKey);
            for (char ch : rows[r].toCharArray()) {
                TextView k = key(String.valueOf(ch), 1f, null);
                k.setOnClickListener(v -> {
                    flash(k);
                    typed(k.getText().toString());
                });
                layerKeys.add(k);
                row.addView(k);
            }
            if (last) row.addView(repeatKey("⌫", 1.5f, listener::onBackspace));
        }
        symbolsKey.setText(layer == SYMBOLS ? LAYER_LETTERS : LAYER_SYMBOLS);
        accentsKey.setText(layer == ACCENTS ? LAYER_LETTERS : LAYER_ACCENTS);
        refreshCase();
    }

    private void setLayer(int l) {
        layer = l;
        buildRows();
    }

    /** Una tecla de las filas del medio: el texto y, si toca, quita la mayúscula de un toque o sale de los acentos. */
    private void typed(String s) {
        listener.onText(s);
        boolean changed = false;
        if (shift && !capsLock && Character.isLetter(s.charAt(0))) {
            shift = false;
            changed = true;
        }
        if (layer == ACCENTS) {
            setLayer(LETTERS);
        } else if (changed) {
            refreshCase();
        }
    }

    /** ⇧: un toque, la siguiente letra en mayúscula; dos seguidos, fijas; otro toque las quita. */
    private void onShift() {
        long now = android.os.SystemClock.uptimeMillis();
        if (capsLock) {
            capsLock = false;
            shift = false;
        } else if (shift && now - lastShiftMs < CAPS_LOCK_MS) {
            capsLock = true;
        } else {
            shift = !shift;
        }
        lastShiftMs = now;
        refreshCase();
    }

    private void refreshCase() {
        boolean upper = shift || capsLock;
        for (TextView k : layerKeys) k.setText(caseOf(k.getText().toString(), upper));
        shiftKey.setText(capsLock ? "⇪" : "⇧");
        shiftKey.setBackground(capsLock ? CarStyle.round(KEY_LIT, 10) : keyBackground());
        shiftKey.setTextColor(capsLock ? 0xFF00211C : shift ? KEY_LIT : CarStyle.TEXT);
    }

    /** Puro: la tecla en mayúscula o minúscula; las que no tienen una sola letra en mayúscula (ß, ¿) se quedan igual. */
    static String caseOf(String key, boolean upper) {
        String t = upper ? key.toUpperCase(Locale.ROOT) : key.toLowerCase(Locale.ROOT);
        return t.length() == key.length() ? t : key;
    }

    /** Enseña encima de las teclas lo escrito (con el cursor) o, vacío, la pista en gris. */
    void showText(CharSequence text, CharSequence hint) {
        cancelReveal();
        boolean empty = text == null || text.length() == 0;
        echo.setText(empty ? hint : text + "▏");
        echo.setTextColor(empty ? CarStyle.TEXT_DIM : CarStyle.TEXT);
        echo.setBackground(CarStyle.round(CarTheme.night() ? 0xFF0F1318 : CarStyle.CARD, 12));
        echo.setVisibility(VISIBLE);
    }

    /**
     * Un campo de una página: su texto, o en una contraseña puntos; con revealLast (se acaba de escribir) la última
     * letra se ve REVEAL_MS y luego pasa a punto, como en el móvil.
     */
    void showField(String value, String hint, boolean secret, boolean revealLast) {
        if (!secret) {
            showText(value, hint == null ? "" : hint);
            return;
        }
        showText(mask(value, revealLast), hint == null ? "" : hint);
        if (revealLast && value != null && !value.isEmpty()) {
            hideLast = () -> showText(mask(value, false), hint == null ? "" : hint);
            echo.postDelayed(hideLast, REVEAL_MS);
        }
    }

    private void cancelReveal() {
        if (hideLast != null) echo.removeCallbacks(hideLast);
        hideLast = null;
    }

    /** Puro: una contraseña en puntos, uno por carácter; con revealLast, el último a la vista. */
    static String mask(String value, boolean revealLast) {
        if (value == null || value.isEmpty()) return "";
        int n = value.codePointCount(0, value.length());
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n - 1; i++) b.append(DOT);
        if (revealLast) b.appendCodePoint(value.codePointBefore(value.length()));
        else b.append(DOT);
        return b.toString();
    }

    private TextView key(String label, float weight, Runnable action) {
        TextView k = CarStyle.text(getContext(), label, 28, CarStyle.TEXT);
        k.setGravity(Gravity.CENTER);
        k.setBackground(keyBackground());
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(4, 4, 4, 4);
        k.setLayoutParams(lp);
        if (action != null) k.setOnClickListener(v -> {
            flash(k);
            action.run();
        });
        return k;
    }

    /** Una tecla que, mantenida, se repite (⌫): al tocar, una vez; tras REPEAT_DELAY_MS, cada REPEAT_MS hasta soltar. */
    @SuppressLint("ClickableViewAccessibility")
    private TextView repeatKey(String label, float weight, Runnable action) {
        TextView k = key(label, weight, null);
        Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            action.run();
            k.postDelayed(repeat[0], REPEAT_MS);
        };
        k.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    flash(k);
                    action.run();
                    k.postDelayed(repeat[0], REPEAT_DELAY_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    k.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
        return k;
    }

    /** Fondo de tecla: normal o, pulsada, iluminada. */
    private static android.graphics.drawable.StateListDrawable keyBackground() {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, CarStyle.round(KEY_LIT, 10));
        d.addState(new int[]{}, CarStyle.round(CarStyle.ITEM_BG, 10));
        return d;
    }

    /** Ilumina la tecla un momento (el estado «pulsada» puede durar menos que un fotograma del vídeo al coche). */
    private void flash(TextView k) {
        k.setBackground(CarStyle.round(KEY_LIT, 10));
        k.setTextColor(0xFF00211C);
        k.postDelayed(() -> {
            if (k == shiftKey) {
                refreshCase();
                return;
            }
            k.setBackground(keyBackground());
            k.setTextColor(CarStyle.TEXT);
        }, FLASH_MS);
    }
}
