package com.headqlink.link;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.andrerinas.openheadunit.R;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Menú ⚙ › Contenido › «Favoritos de radio» en el móvil (foro #342): las emisoras con ★ del coche, para ordenarlas
 * arrastrando el asa, cambiarles el nombre (tocándolas) y quitarlas (✕, tras preguntar). Cada cambio se guarda al momento
 * (Favorites.RADIO) y Android Auto vuelve a pedir la carpeta; la pestaña ★ del coche las lee al abrirla. Solo se tocan el
 * orden y el nombre: la URL guardada (su clave, con las marcas de su usuario), el id del usuario y su servidor siguen
 * igual, y la URL no sale en pantalla (solo su servidor).
 */
final class RadioFavsEditor {
    private RadioFavsEditor() {
    }

    static void show(Activity a) {
        Config cfg = new Config(a);
        JSONArray favs = Favorites.load(cfg, Favorites.RADIO);
        float d = a.getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView help = new TextView(a);
        help.setTextColor(a.getColor(R.color.hql_text_dim));
        box.addView(help);
        RecyclerView list = new RecyclerView(a);
        list.setLayoutManager(new LinearLayoutManager(a));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = pad / 3;
        box.addView(list, llp);
        Runnable empty = () -> help.setText(Str.get(favs.length() == 0 ? R.string.hql_radio_favs_none : R.string.hql_radio_favs_help));
        empty.run();
        Adapter adapter = new Adapter(a, favs, () -> {
            Favorites.save(new Config(a), Favorites.RADIO, favs);
            RadioBrowserService.changed(RadioLibrary.FAVS);
            empty.run();
        });
        ItemTouchHelper drag = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            /** Se ha movido alguna desde que se cogió: al soltarla, se guarda. */
            private boolean moved;

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition(), to = target.getBindingAdapterPosition();
                if (from < 0 || to < 0) return false;
                Favorites.move(favs, from, to);
                adapter.notifyItemMoved(from, to);
                moved = true;
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                // Sin deslizar para quitar: con el ✕ y preguntando.
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // Solo desde el asa (como en Conexión automática); tocar la fila es renombrar.
                return false;
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                if (!moved) return;
                moved = false;
                adapter.changed.run();
                L.i("radio: favoritos reordenados en el móvil (" + favs.length() + ")");
            }
        });
        adapter.drag = drag;
        list.setAdapter(adapter);
        drag.attachToRecyclerView(list);
        new MaterialAlertDialogBuilder(a)
                .setTitle(Str.get(R.string.hql_radio_favs))
                .setView(box)
                .setPositiveButton(Str.get(R.string.hql_close), null)
                .show();
    }

    /** Pregunta por el nombre nuevo (el de ahora escrito); vacío vuelve al de su lista. */
    private static void rename(Activity a, JSONObject o, Runnable done) {
        EditText field = M3.field(a);
        field.setHint(Str.get(R.string.hql_bookmark_name));
        field.setSingleLine(true);
        field.setText(o.optString("name"));
        field.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(M3.outlined(field));
        TextView back = new TextView(a);
        back.setText(Str.get(R.string.hql_radio_fav_rename_help, Favorites.originalName(o)));
        back.setTextColor(a.getColor(R.color.hql_text_dim));
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        back.setPadding(0, pad / 4, 0, 0);
        box.addView(back);
        new MaterialAlertDialogBuilder(a)
                .setTitle(Str.get(R.string.hql_radio_fav_rename))
                .setView(box)
                .setPositiveButton(Str.get(R.string.hql_save), (x, w) -> {
                    Favorites.rename(o, field.getText().toString());
                    L.i("radio: favorita renombrada en el móvil" + (Favorites.renamed(o) ? "" : " (vuelve a su nombre)"));
                    done.run();
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show();
        field.requestFocus();
    }

    /** Puro: la segunda línea de una favorita: el nombre de su lista (si lleva otro), su grupo y su servidor (sin la URL). */
    static String detail(JSONObject o) {
        StringBuilder b = new StringBuilder();
        if (Favorites.renamed(o)) b.append(Favorites.originalName(o));
        String group = o.optString("group");
        if (!group.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(group);
        String host = Uri.parse(o.optString("url")).getHost();
        if (host != null && !host.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(host);
        return b.toString();
    }

    /** Las filas: asa, logo, nombre (y detail) y ✕. */
    private static final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Activity a;
        private final JSONArray favs;
        /** Tras cada cambio: guardar, avisar a Android Auto y la ayuda (o «aún no hay»). */
        final Runnable changed;
        ItemTouchHelper drag;

        Adapter(Activity a, JSONArray favs, Runnable changed) {
            this.a = a;
            this.favs = favs;
            this.changed = changed;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Math.round(56 * d));
            TypedValue tv = new TypedValue();
            a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView handle = new ImageView(a);
            handle.setImageResource(R.drawable.ic_drag_handle);
            handle.setImageTintList(ColorStateList.valueOf(a.getColor(R.color.hql_text_dim)));
            handle.setContentDescription(a.getString(R.string.drag_to_reorder));
            int hp = Math.round(8 * d);
            handle.setPadding(0, hp, hp, hp);
            row.addView(handle, new LinearLayout.LayoutParams(Math.round(32 * d), Math.round(40 * d)));
            ImageView logo = new ImageView(a);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int ls = Math.round(36 * d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ls, ls);
            lp.rightMargin = Math.round(12 * d);
            row.addView(logo, lp);
            TextView text = new TextView(a);
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            MaterialButton remove = new MaterialButton(a, null, com.google.android.material.R.attr.materialIconButtonStyle);
            remove.setIconResource(R.drawable.ic_close_white);
            remove.setIconTint(ColorStateList.valueOf(a.getColor(R.color.hql_text_dim)));
            remove.setContentDescription(Str.get(R.string.hql_remove));
            row.addView(remove);
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            JSONObject o = favs.optJSONObject(pos);
            if (o == null) o = new JSONObject();
            LinearLayout row = (LinearLayout) vh.itemView;
            ImageView handle = (ImageView) row.getChildAt(0);
            ImageView logo = (ImageView) row.getChildAt(1);
            TextView text = (TextView) row.getChildAt(2);
            String name = o.optString("name");
            String detail = detail(o);
            SpannableStringBuilder sb = new SpannableStringBuilder(name.isEmpty() ? "—" : name);
            int span = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            sb.setSpan(new ForegroundColorSpan(a.getColor(R.color.hql_text)), 0, sb.length(), span);
            if (!detail.isEmpty()) {
                int at = sb.length();
                sb.append('\n').append(detail);
                sb.setSpan(new ForegroundColorSpan(a.getColor(R.color.hql_text_dim)), at, sb.length(), span);
                sb.setSpan(new RelativeSizeSpan(0.8f), at, sb.length(), span);
            }
            text.setText(sb);
            String url = o.optString("logo");
            if (!url.isEmpty()) Glide.with(a).load(url).error(R.drawable.hql_ic_radio).into(logo);
            else {
                Glide.with(a).clear(logo);
                logo.setImageResource(R.drawable.hql_ic_radio);
            }
            handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && drag != null) drag.startDrag(vh);
                return false;
            });
            row.setOnClickListener(v -> {
                int i = vh.getBindingAdapterPosition();
                JSONObject cur = i < 0 ? null : favs.optJSONObject(i);
                if (cur == null) return;
                rename(a, cur, () -> {
                    changed.run();
                    int at = indexOf(favs, cur);
                    if (at >= 0) notifyItemChanged(at);
                });
            });
            row.getChildAt(3).setOnClickListener(v -> {
                int i = vh.getBindingAdapterPosition();
                JSONObject cur = i < 0 ? null : favs.optJSONObject(i);
                if (cur == null) return;
                new MaterialAlertDialogBuilder(a)
                        .setMessage(Str.get(R.string.hql_radio_fav_remove_q, cur.optString("name")))
                        .setPositiveButton(Str.get(R.string.hql_remove), (x, w) -> {
                            // Por si entretanto se ha movido: la misma, por su sitio de ahora.
                            int at = indexOf(favs, cur);
                            if (at < 0) return;
                            Favorites.removeAt(favs, at);
                            notifyItemRemoved(at);
                            changed.run();
                            L.i("radio: favorita quitada en el móvil (" + favs.length() + ")");
                        })
                        .setNegativeButton(Str.get(R.string.hql_cancel), null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return favs.length();
        }

        private static int indexOf(JSONArray a, JSONObject o) {
            for (int i = 0; i < a.length(); i++) if (a.opt(i) == o) return i;
            return -1;
        }
    }
}
