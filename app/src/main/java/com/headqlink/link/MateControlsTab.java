package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Pestaña «Mandos» de la sección Coche, con Leapmotor Mate en Home Assistant: sus botones (MateControls) con lo que
 * dice Mate de ahora, releído cada 10 s mientras se ve (MateFeed). Lo que abre el coche pide confirmar.
 */
final class MateControlsTab implements CarScreen {
    private Host host;
    private FrameLayout frame;
    private MateControls controls;
    private TextView message;
    private TextView status;
    private final Runnable onRead = this::refresh;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        frame = new FrameLayout(c);
        frame.setBackgroundColor(CarKit.BG);
        controls = new MateControls(c, h.width(), this::act);
        frame.addView(controls.view(), CarStyle.match());
        message = CarStyle.text(c, Str.get(R.string.hql_ha_loading), 26, CarStyle.TEXT_DIM);
        message.setGravity(Gravity.CENTER);
        frame.addView(message, CarStyle.match());
        status = CarStyle.text(c, "", 21, CarKit.AMBER);
        frame.addView(status, CarKit.at(Gravity.TOP | Gravity.END, 0, 22, 34, 0));
        refresh();
        MateFeed.listen(onRead);
        MateFeed.acquire(c, "controls", true);
        return frame;
    }

    @Override
    public void destroy() {
        MateFeed.unlisten(onRead);
        MateFeed.release("controls");
    }

    private void refresh() {
        if (frame == null) return;
        MateHa.Car car = MateFeed.car();
        String err = MateFeed.error();
        status.setText(err == null ? "" : Str.get(R.string.hql_ha_error, err));
        if (car == null) {
            if (err != null) message.setText(Str.get(R.string.hql_ha_error, err));
            message.setVisibility(View.VISIBLE);
            controls.view().setVisibility(View.GONE);
            return;
        }
        message.setVisibility(View.GONE);
        controls.view().setVisibility(View.VISIBLE);
        controls.update(car);
    }

    private void act(String domain, String service, String entityId, JSONObject data, String confirm) {
        if (entityId == null) return;
        Runnable go = () -> {
            controls.busy = entityId;
            controls.update(MateFeed.car());
            MateFeed.call(host.context(), domain, service, entityId, data, err -> {
                controls.busy = null;
                if (err != null) status.setText(Str.get(R.string.hql_ha_error, err));
                refresh();
            });
        };
        if (confirm != null) HomeScreen.confirm(frame, confirm, go);
        else go.run();
    }
}
