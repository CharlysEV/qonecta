package com.headqlink.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

import com.andrerinas.openheadunit.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Acuario para los niños (Juegos, con el coche parado): peces de colores que nadan a su aire, burbujas, algas que se
 * mecen y rayos de luz. Tocar el agua echa comida (los peces van a por ella); tocar un pez lo asusta; «Otro pez» añade
 * uno. Todo dibujado aquí (sin imágenes), al ritmo de la pantalla.
 */
final class AquariumView extends GamesScreen.Board {
    static final int START_FISH = 6;
    static final int MAX_FISH = 14;
    /** Escamas de comida por toque, y como mucho a la vez. */
    static final int FOOD_PER_TAP = 5;
    static final int MAX_FOOD = 40;

    /** Colores de cada especie: cuerpo, aleta/cola, rayas (0 = sin rayas). */
    private static final int[][] KINDS = {
            {0xFFFF7A1A, 0xFFFF9A45, 0xFFFFFFFF}, // payaso
            {0xFF2E6BEB, 0xFFFFD23F, 0},          // cirujano azul
            {0xFFFFD23F, 0xFFFFB300, 0},          // cirujano amarillo
            {0xFF9C6ADE, 0xFF6A3FB5, 0},          // morado
            {0xFFE8505B, 0xFFB83240, 0xFFFFE0E0}, // rojo con rayas claras
            {0xFF3FC7F0, 0xFFE8505B, 0}};         // neón

    static final class Fish {
        float x, y, vx, vy;
        float tx, ty;
        float size;
        float phase;
        int kind;
        /** Hacia dónde mira (1 derecha, -1 izquierda): cambia con algo de margen para que no tiemble. */
        float dir = 1;
        float speed;
        long wanderUntilMs;
        long scaredUntilMs;
    }

    static final class Food {
        float x, y, drift;
    }

    static final class Bubble {
        float x, y, r, speed, phase;
    }

    final List<Fish> fish = new ArrayList<>();
    final List<Food> food = new ArrayList<>();
    private final List<Bubble> bubbles = new ArrayList<>();
    private final Random rnd;
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final RectF mouth = new RectF();
    private float[] weedX = new float[0];
    private float[] weedH = new float[0];
    private float[] weedPhase = new float[0];
    private float[] pebbles = new float[0];
    private LinearGradient water;
    private int waterH;
    private long lastMs;
    private long nextBubbleMs;
    private float t;
    /** Vista previa: dibujar un instante fijo, sin animación. */
    private boolean still;

    AquariumView(Context c) {
        this(c, new Random());
    }

    AquariumView(Context c, Random rnd) {
        super(c);
        this.rnd = rnd;
        status = Str.get(R.string.hql_aquarium_status);
    }

    @Override
    void reset() {
        fish.clear();
        food.clear();
        bubbles.clear();
        if (getWidth() > 0) for (int i = 0; i < START_FISH; i++) addFish();
        updateStatus();
    }

    @Override
    String againLabel() {
        return Str.get(R.string.hql_aquarium_more);
    }

    /** «Otro pez»: uno más (hasta MAX_FISH). */
    @Override
    void again() {
        if (fish.size() < MAX_FISH) addFish();
        updateStatus();
    }

    @Override
    void demo() {
        still = true;
        if (getWidth() > 0) scene();
    }

    private void updateStatus() {
        status = Str.plural(R.plurals.hql_aquarium_fish, fish.size()) + " · " + Str.get(R.string.hql_aquarium_status);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        waterH = h;
        water = new LinearGradient(0, 0, 0, h, new int[]{0xFF39C2D7, 0xFF1479A8, 0xFF07335A}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);
        // Algas y piedras a lo largo del fondo, siempre en el mismo sitio para este tamaño.
        Random r = new Random(w * 31L + h);
        int n = Math.max(5, w / 240);
        weedX = new float[n];
        weedH = new float[n];
        weedPhase = new float[n];
        for (int i = 0; i < n; i++) {
            weedX[i] = (i + 0.2f + r.nextFloat() * 0.6f) * w / n;
            weedH[i] = h * (0.22f + r.nextFloat() * 0.3f);
            weedPhase[i] = r.nextFloat() * 6.28f;
        }
        pebbles = new float[3 * Math.max(8, w / 90)];
        for (int i = 0; i < pebbles.length; i += 3) {
            pebbles[i] = r.nextFloat() * w;
            pebbles[i + 1] = sandTop(pebbles[i]) + 8 + r.nextFloat() * (h - sandTop(pebbles[i]) - 14);
            pebbles[i + 2] = 3 + r.nextFloat() * 6;
        }
        if (fish.isEmpty()) {
            if (still) scene();
            else reset();
        } else {
            for (Fish f : fish) clampInside(f);
        }
    }

    /** Vista previa: los peces repartidos, algo de comida cayendo y burbujas. */
    private void scene() {
        fish.clear();
        food.clear();
        bubbles.clear();
        Random r = new Random(7);
        for (int i = 0; i < START_FISH + 1; i++) {
            Fish f = newFish(r, i % KINDS.length);
            f.x = getWidth() * (0.1f + 0.8f * (i + 0.5f) / (START_FISH + 1));
            f.y = swimTop() + (swimBottom() - swimTop()) * (0.15f + 0.7f * r.nextFloat());
            f.dir = f.vx = i % 2 == 0 ? 1 : -1;
            fish.add(f);
        }
        for (int i = 0; i < FOOD_PER_TAP; i++) {
            Food fd = new Food();
            fd.x = getWidth() * 0.62f + (i - 2) * 14;
            fd.y = getHeight() * 0.18f + i * 22;
            food.add(fd);
        }
        for (int i = 0; i < 9; i++) {
            Bubble b = new Bubble();
            b.x = getWidth() * (i < 5 ? 0.18f : 0.82f) + r.nextFloat() * 12;
            b.y = getHeight() * (0.15f + 0.08f * i);
            b.r = 5 + r.nextFloat() * 9;
            bubbles.add(b);
        }
        t = 1.3f;
        updateStatus();
    }

    // ------------------------------------------------------------------ peces

    private float swimTop() {
        return getHeight() * 0.1f;
    }

    private float swimBottom() {
        return getHeight() * 0.78f;
    }

    /** Altura de la arena en x (ondulada). */
    private float sandTop(float x) {
        float h = waterH > 0 ? waterH : getHeight();
        return h * 0.86f + (float) Math.sin(x / 140f) * h * 0.02f + (float) Math.sin(x / 47f + 1) * h * 0.006f;
    }

    private float scale() {
        return Math.max(0.6f, Math.min(getWidth(), getHeight() * 2f) / 1600f);
    }

    private Fish newFish(Random r, int kind) {
        Fish f = new Fish();
        f.kind = kind;
        f.size = (70 + r.nextFloat() * 70) * scale() * (kind == 5 ? 0.7f : 1f);
        f.phase = r.nextFloat() * 6.28f;
        f.speed = (55 + r.nextFloat() * 55) * scale();
        return f;
    }

    private void addFish() {
        Fish f = newFish(rnd, rnd.nextInt(KINDS.length));
        // Entra por un lado.
        boolean left = rnd.nextBoolean();
        f.x = left ? -f.size : getWidth() + f.size;
        f.y = swimTop() + rnd.nextFloat() * (swimBottom() - swimTop());
        f.dir = left ? 1 : -1;
        f.vx = f.dir * f.speed;
        newTarget(f, 0);
        // Que nade hacia el centro antes de pasear.
        f.tx = getWidth() * (0.3f + rnd.nextFloat() * 0.4f);
        fish.add(f);
    }

    private void newTarget(Fish f, long now) {
        f.tx = getWidth() * (0.05f + rnd.nextFloat() * 0.9f);
        f.ty = swimTop() + rnd.nextFloat() * (swimBottom() - swimTop());
        f.wanderUntilMs = now + 3000 + rnd.nextInt(5000);
    }

    private void clampInside(Fish f) {
        f.y = Math.max(swimTop(), Math.min(swimBottom(), f.y));
    }

    void step(float dt, long now) {
        t += dt;
        // Comida: cae despacio, con vaivén; la que llega al fondo se queda un rato y desaparece.
        for (Iterator<Food> it = food.iterator(); it.hasNext(); ) {
            Food fd = it.next();
            float floor = sandTop(fd.x) - 4;
            if (fd.y < floor) {
                fd.y = Math.min(floor, fd.y + 38 * scale() * dt);
                fd.x += (float) Math.sin(t * 2 + fd.drift) * 12 * dt;
            } else {
                fd.drift += dt;
                if (fd.drift > 30) it.remove();
            }
        }
        for (Fish f : fish) {
            float speed = f.speed;
            Food goal = null;
            if (now < f.scaredUntilMs) {
                speed *= 2.6f;
            } else {
                goal = nearestFood(f);
                if (goal != null) {
                    f.tx = goal.x;
                    f.ty = Math.min(goal.y, swimBottom());
                    speed *= 1.7f;
                } else if (now > f.wanderUntilMs || Math.hypot(f.tx - f.x, f.ty - f.y) < f.size) {
                    newTarget(f, now);
                }
            }
            float dx = f.tx - f.x;
            float dy = f.ty - f.y;
            float d = (float) Math.max(1, Math.hypot(dx, dy));
            float wantVx = dx / d * speed;
            float wantVy = dy / d * speed * 0.6f;
            float k = Math.min(1, dt * 1.6f);
            f.vx += (wantVx - f.vx) * k;
            f.vy += (wantVy - f.vy) * k;
            f.x += f.vx * dt;
            f.y += f.vy * dt;
            clampInside(f);
            if (f.vx > 12) f.dir = 1;
            else if (f.vx < -12) f.dir = -1;
            f.phase += dt * (5 + Math.abs(f.vx) / Math.max(1, f.speed) * 6);
            // Come la que tiene en la boca.
            if (goal != null && Math.hypot(goal.x - (f.x + f.dir * f.size * 0.45f), goal.y - f.y) < f.size * 0.35f) {
                food.remove(goal);
                bubble(f.x + f.dir * f.size * 0.5f, f.y - f.size * 0.1f, 4 * scale());
            }
        }
        // Burbujas: de dos sitios del fondo cada poco, y suben con vaivén.
        if (now >= nextBubbleMs) {
            nextBubbleMs = now + 350 + rnd.nextInt(900);
            float x = getWidth() * (rnd.nextBoolean() ? 0.18f : 0.82f) + rnd.nextFloat() * 14;
            bubble(x, sandTop(x) - 6, (4 + rnd.nextFloat() * 9) * scale());
        }
        for (Iterator<Bubble> it = bubbles.iterator(); it.hasNext(); ) {
            Bubble b = it.next();
            b.y -= b.speed * dt;
            b.phase += dt * 3;
            if (b.y < -b.r) it.remove();
        }
    }

    private Food nearestFood(Fish f) {
        Food best = null;
        double bd = Double.MAX_VALUE;
        for (Food fd : food) {
            double d = Math.hypot(fd.x - f.x, fd.y - f.y);
            if (d < bd) {
                bd = d;
                best = fd;
            }
        }
        // Solo van a por ella los que la tienen cerca (o si hay poca, todos).
        return best != null && (bd < getWidth() * 0.45f || food.size() <= 2) ? best : null;
    }

    private void bubble(float x, float y, float r) {
        if (bubbles.size() > 60) return;
        Bubble b = new Bubble();
        b.x = x;
        b.y = y;
        b.r = r;
        b.speed = (45 + rnd.nextFloat() * 40) * scale();
        b.phase = rnd.nextFloat() * 6.28f;
        bubbles.add(b);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        float x = e.getX();
        float y = e.getY();
        long now = System.currentTimeMillis();
        // ¿Un pez? Se asusta y sale disparado al otro lado.
        for (Fish f : fish) {
            if (Math.abs(x - f.x) < f.size * 0.6f && Math.abs(y - f.y) < f.size * 0.35f) {
                f.scaredUntilMs = now + 1200;
                f.tx = x < getWidth() / 2f ? getWidth() * 0.92f : getWidth() * 0.08f;
                f.ty = swimTop() + rnd.nextFloat() * (swimBottom() - swimTop());
                for (int i = 0; i < 4; i++) bubble(f.x + rnd.nextFloat() * 20 - 10, f.y - i * 10, (4 + i * 2) * scale());
                return true;
            }
        }
        // Agua: comida.
        for (int i = 0; i < FOOD_PER_TAP && food.size() < MAX_FOOD; i++) {
            Food fd = new Food();
            fd.x = x + (rnd.nextFloat() - 0.5f) * 60;
            fd.y = Math.min(y, swimBottom()) + (rnd.nextFloat() - 0.5f) * 30;
            fd.drift = rnd.nextFloat() * 6.28f;
            food.add(fd);
        }
        return true;
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || water == null) return;
        long now = System.currentTimeMillis();
        if (!still) {
            float dt = lastMs == 0 ? 0 : Math.min(0.05f, (now - lastMs) / 1000f);
            lastMs = now;
            step(dt, now);
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(water);
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        drawRays(cv, w, h);
        drawWeeds(cv, h);
        drawSand(cv, w, h);
        for (Food fd : food) {
            p.setColor(0xFFC8742E);
            cv.drawCircle(fd.x, fd.y, 4.5f * scale(), p);
        }
        for (Fish f : fish) drawFish(cv, f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f * scale());
        for (Bubble b : bubbles) {
            float bx = b.x + (float) Math.sin(b.phase) * 6;
            p.setColor(0xB3FFFFFF);
            cv.drawCircle(bx, b.y, b.r, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x66FFFFFF);
            cv.drawCircle(bx - b.r * 0.35f, b.y - b.r * 0.35f, b.r * 0.25f, p);
            p.setStyle(Paint.Style.STROKE);
        }
        p.setStyle(Paint.Style.FILL);
        if (!still && isShown()) postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastMs = 0;
        invalidate();
    }

    private void drawRays(Canvas cv, int w, int h) {
        p.setColor(0x16FFFFFF);
        for (int i = 0; i < 4; i++) {
            float x = w * (0.12f + 0.25f * i) + (float) Math.sin(t * 0.25f + i) * w * 0.03f;
            path.reset();
            path.moveTo(x - w * 0.03f, 0);
            path.lineTo(x + w * 0.05f, 0);
            path.lineTo(x + w * 0.16f, h * 0.85f);
            path.lineTo(x + w * 0.02f, h * 0.85f);
            path.close();
            cv.drawPath(path, p);
        }
    }

    private void drawWeeds(Canvas cv, int h) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < weedX.length; i++) {
            float x0 = weedX[i];
            float y0 = sandTop(x0) + 6;
            float top = y0 - weedH[i];
            for (int s = 0; s < 3; s++) {
                float off = (s - 1) * 16 * scale();
                float sway = (float) Math.sin(t * 0.9f + weedPhase[i] + s) * 34 * scale();
                path.reset();
                path.moveTo(x0 + off, y0);
                path.cubicTo(x0 + off + sway * 0.2f, y0 - weedH[i] * 0.35f, x0 + off - sway * 0.6f, y0 - weedH[i] * 0.7f,
                        x0 + off + sway, top + s * 18);
                p.setStrokeWidth((14 - s * 2) * scale());
                p.setColor(s == 1 ? 0xFF2E9E5B : 0xFF1F7A45);
                cv.drawPath(path, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawSand(Canvas cv, int w, int h) {
        path.reset();
        path.moveTo(0, h);
        for (int x = 0; x <= w; x += 24) path.lineTo(x, sandTop(x));
        path.lineTo(w, sandTop(w));
        path.lineTo(w, h);
        path.close();
        p.setColor(0xFFE2C58F);
        cv.drawPath(path, p);
        p.setColor(0xFFC9A86A);
        for (int i = 0; i < pebbles.length; i += 3) cv.drawCircle(pebbles[i], pebbles[i + 1], pebbles[i + 2] * scale(), p);
        // Una estrella de mar y una concha.
        drawStar(cv, w * 0.68f, (sandTop(w * 0.68f) + h) / 2f, 30 * scale());
        float sx = w * 0.3f;
        float sy = (sandTop(sx) + h) / 2f;
        float sr = 26 * scale();
        p.setColor(0xFFF5D0C5);
        oval.set(sx - sr, sy - sr * 0.8f, sx + sr, sy + sr * 0.8f);
        cv.drawArc(oval, 180, 180, true, p);
        p.setColor(0xFFE0A899);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * scale());
        for (int k = -2; k <= 2; k++) cv.drawLine(sx, sy, sx + k * sr * 0.4f, sy - sr * 0.7f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawStar(Canvas cv, float cx, float cy, float r) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? r : r * 0.45f;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        p.setColor(0xFFFF8A65);
        cv.drawPath(path, p);
    }

    private void drawFish(Canvas cv, Fish f) {
        int[] k = KINDS[f.kind];
        float L = f.size;
        cv.save();
        cv.translate(f.x, f.y);
        cv.scale(f.dir, 1);
        // Inclinado según suba o baje.
        cv.rotate((float) Math.toDegrees(Math.atan2(f.vy, Math.max(30, Math.abs(f.vx)))) * 0.5f);
        float wag = (float) Math.sin(f.phase) * 0.12f * L;
        // Cola.
        path.reset();
        path.moveTo(-0.38f * L, 0);
        path.lineTo(-0.72f * L, -0.26f * L + wag);
        path.quadTo(-0.62f * L, wag * 0.5f, -0.72f * L, 0.26f * L + wag);
        path.close();
        p.setColor(k[1]);
        cv.drawPath(path, p);
        // Aleta de arriba.
        path.reset();
        path.moveTo(-0.2f * L, -0.2f * L);
        path.quadTo(0.0f, -0.42f * L, 0.18f * L, -0.2f * L);
        path.close();
        cv.drawPath(path, p);
        // Cuerpo.
        oval.set(-0.48f * L, -0.24f * L, 0.48f * L, 0.24f * L);
        p.setColor(k[0]);
        cv.drawOval(oval, p);
        if (k[2] != 0) {
            // Rayas, recortadas al cuerpo.
            cv.save();
            path.reset();
            path.addOval(oval, Path.Direction.CW);
            cv.clipPath(path);
            p.setColor(k[2]);
            for (float sx : new float[]{0.18f, -0.12f}) cv.drawRect((sx - 0.06f) * L, -0.3f * L, (sx + 0.06f) * L, 0.3f * L, p);
            cv.restore();
        }
        // Aleta lateral, que se mueve.
        path.reset();
        path.moveTo(0.02f * L, 0.04f * L);
        path.quadTo(-0.1f * L, 0.18f * L + wag * 0.6f, -0.18f * L, 0.1f * L);
        path.close();
        p.setColor(k[1]);
        cv.drawPath(path, p);
        // Ojo y boca.
        p.setColor(0xFFFFFFFF);
        cv.drawCircle(0.27f * L, -0.06f * L, 0.075f * L, p);
        p.setColor(0xFF111111);
        cv.drawCircle(0.29f * L, -0.06f * L, 0.04f * L, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, 0.02f * L));
        p.setColor(0x99111111);
        mouth.set(0.36f * L, 0.0f, 0.46f * L, 0.08f * L);
        cv.drawArc(mouth, 90, 90, false, p);
        p.setStyle(Paint.Style.FILL);
        cv.restore();
    }
}
