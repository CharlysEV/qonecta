package com.headqlink.link;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Iconos del panel extendido del coche (CarUi y Ajustes del coche): fichas redondeadas con degradado, brillo superior,
 * bisel luminoso e ilustraciones vectoriales en varias capas, dibujadas en código para verse nítidas a cualquier tamaño
 * (barra de iconos, panel desplegado, pantalla reducida del T03, modo día y noche) sin tocar los vectores monocromos que
 * usan las notificaciones o el móvil.
 */
final class PanelIcons {
    private PanelIcons() {
    }

    /** Crea la ficha de icono para un botón del panel por su id («aa», «car», «photos», …, «split», «swap», «expand»). */
    static Tile forNav(String id) {
        return new Tile(id == null ? "settings" : id);
    }

    /** Fondo de ficha translúcido para los accesos fijados de Home Assistant en la barra. */
    static Drawable haBadge(boolean dark) {
        return new HaBadge(dark);
    }

    /**
     * Píldora activa del panel lateral: cristal tintado con el acento, borde luminoso y barra vertical en el borde
     * exterior, diseñada para enmarcar las fichas de color sin chocar con ellas (en panel oscuro y claro).
     */
    static Drawable activePill(boolean dark, boolean panelRight) {
        return new ActivePill(dark, panelRight);
    }

    /** Todos los identificadores soportados (útil en pruebas). */
    static final String[] ALL_IDS = {
            "aa", "car", "photos", "videos", "web", "tv", "radio", "games",
            "home", "guide", "karaoke", "companion", "split", "swap", "settings", "expand"
    };

    /** Paleta de degradado (arriba-izquierda → abajo-derecha) de cada ficha. */
    static int[] palette(String id, boolean alert, boolean darkSurface) {
        if ("car".equals(id) && alert) return new int[]{0xFFFFB547, 0xFFEA580C};
        switch (id) {
            case "aa":        return new int[]{0xFF1FD1F9, 0xFF1D4ED8};
            case "car":       return new int[]{0xFF00E5C7, 0xFF059669};
            case "photos":    return new int[]{0xFFFF5E7E, 0xFFF59E0B};
            case "videos":    return new int[]{0xFFFF3B5C, 0xFFBE123C};
            case "web":       return new int[]{0xFF38BDF8, 0xFF2563EB};
            case "tv":        return new int[]{0xFFA855F7, 0xFF5B21B6};
            case "radio":     return new int[]{0xFFFF9F1C, 0xFFDC2626};
            case "games":     return new int[]{0xFFF43F5E, 0xFF7C3AED};
            case "home":      return new int[]{0xFF29B6F6, 0xFF0369A1};
            case "guide":     return new int[]{0xFF34D399, 0xFF047857};
            case "karaoke":   return new int[]{0xFFEC4899, 0xFF7E22CE};
            case "companion": return new int[]{0xFF06B6D4, 0xFF6366F1};
            case "split":     return new int[]{0xFF14B8A6, 0xFF0369A1};
            case "swap":      return new int[]{0xFF38BDF8, 0xFF1D4ED8};
            case "expand":
                return darkSurface ? new int[]{0xFF3E4B5C, 0xFF1E2631} : new int[]{0xFFFFFFFF, 0xFFD5DDE7};
            default:          return new int[]{0xFF64748B, 0xFF1E293B}; // settings
        }
    }

    /** Ficha de icono vectorial con degradado, bisel de cristal y dibujo multicapa. */
    static final class Tile extends Drawable {
        final String id;
        private boolean selected;
        private boolean alert;
        private boolean darkSurface = true;
        private int alpha = 255;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final RectF tmp = new RectF();
        private final Path path = new Path();
        private final Path path2 = new Path();
        private final Path clip = new Path();

        // Shaders y efectos cacheados por tamaño y estado: cero asignaciones al repintar.
        private float cachedSize = -1f;
        private boolean shadersDirty = true;
        private Shader bgShader;
        private Shader bloomShader;
        private Shader sheenShader;
        private Shader rimShader;
        private Shader gearShader;
        private CornerPathEffect cpe14;
        private CornerPathEffect cpe24;
        private CornerPathEffect cpe32;
        private CornerPathEffect cpe46;

        Tile(String id) {
            this.id = id;
        }

        void setSelected(boolean on) {
            if (selected == on) return;
            selected = on;
            shadersDirty = true;
            invalidateSelf();
        }

        void setAlert(boolean on) {
            if (alert == on) return;
            alert = on;
            shadersDirty = true;
            invalidateSelf();
        }

        void setDarkSurface(boolean dark) {
            if (darkSurface == dark) return;
            darkSurface = dark;
            shadersDirty = true;
            invalidateSelf();
        }

        boolean isSelected() {
            return selected;
        }

        boolean isAlert() {
            return alert;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            shadersDirty = true;
        }

        private void ensureShaders(float s) {
            if (!shadersDirty && Math.abs(s - cachedSize) < 0.5f) return;
            cachedSize = s;
            shadersDirty = false;
            int[] pal = palette(id, alert, darkSurface);
            bgShader = new LinearGradient(box.left, box.top, box.right, box.bottom,
                    pal[0], pal[1], Shader.TileMode.CLAMP);
            bloomShader = new RadialGradient(
                    box.left + box.width() * 0.24f, box.top + box.height() * 0.16f,
                    Math.max(1f, box.width() * 0.85f),
                    "expand".equals(id) && !darkSurface ? 0x60FFFFFF : 0x42FFFFFF,
                    0x00FFFFFF, Shader.TileMode.CLAMP);
            sheenShader = new LinearGradient(box.left, box.top, box.left, box.top + box.height() * 0.52f,
                    "expand".equals(id) && !darkSurface ? 0x55FFFFFF : 0x2AFFFFFF,
                    0x00FFFFFF, Shader.TileMode.CLAMP);
            int rimTop = selected ? 0xCCFFFFFF : ("expand".equals(id) && !darkSurface ? 0xFFFFFFFF : 0x66FFFFFF);
            int rimBot = selected ? 0x8000E5C7 : 0x33000000;
            rimShader = new LinearGradient(box.left, box.top, box.left, box.bottom,
                    rimTop, rimBot, Shader.TileMode.CLAMP);
            float u = box.width() / 100f;
            float rOut = 29.5f * u;
            gearShader = new LinearGradient(0, -rOut, 0, rOut,
                    0xFFFFFFFF, 0xFFCBD5E1, Shader.TileMode.CLAMP);
            cpe14 = new CornerPathEffect(Math.max(0.5f, 1.4f * u));
            cpe24 = new CornerPathEffect(Math.max(0.5f, 2.4f * u));
            cpe32 = new CornerPathEffect(Math.max(0.5f, 3.2f * u));
            cpe46 = new CornerPathEffect(Math.max(0.5f, 4.6f * u));
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h);
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            // Margen de 5 % alrededor de la ficha para la sombra suave y el halo cuando está activa.
            float half = s * 0.45f;
            box.set(cx - half, cy - half, cx + half, cy + half);
            float bw = box.width();
            float cr = bw * 0.28f;
            ensureShaders(s);

            int saved = cv.save();
            if (alpha < 255) cv.saveLayerAlpha(b.left, b.top, b.right, b.bottom, alpha);

            // 1. Sombra suave inferior (despega la ficha del panel).
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(darkSurface ? 0x48000000 : 0x26000000);
            tmp.set(box.left, box.top + s * 0.032f, box.right, box.bottom + s * 0.038f);
            cv.drawRoundRect(tmp, cr, cr, paint);

            // 2. Halo luminoso cuando esta sección está activa.
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.5f, s * 0.042f));
                paint.setColor(darkSurface ? 0x6600E5C7 : 0x5500897B);
                tmp.set(box.left - s * 0.02f, box.top - s * 0.02f, box.right + s * 0.02f, box.bottom + s * 0.02f);
                cv.drawRoundRect(tmp, cr + s * 0.02f, cr + s * 0.02f, paint);
            }

            // 3. Cuerpo principal con degradado (opacidad completa: resetear alpha tras la sombra/halo).
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(bgShader);
            cv.drawRoundRect(box, cr, cr, paint);

            // 4. Iluminación ambiental radial arriba-izquierda y brillo de cristal superior.
            cv.save();
            clip.rewind();
            clip.addRoundRect(box, cr, cr, Path.Direction.CW);
            cv.clipPath(clip);
            paint.setAlpha(255);
            paint.setShader(bloomShader);
            cv.drawRect(box, paint);
            paint.setShader(sheenShader);
            tmp.set(box.left - bw * 0.15f, box.top - bw * 0.42f, box.right + bw * 0.15f, box.top + bw * 0.46f);
            cv.drawOval(tmp, paint);
            cv.restore();

            // 5. Bisel interior (arriba luminoso, abajo sutilmente oscuro).
            paint.setAlpha(255);
            paint.setShader(rimShader);
            paint.setStyle(Paint.Style.STROKE);
            float rimW = Math.max(1.1f, s * (selected ? 0.034f : 0.024f));
            paint.setStrokeWidth(rimW);
            float inset = rimW * 0.5f;
            tmp.set(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset);
            cv.drawRoundRect(tmp, Math.max(1f, cr - inset), Math.max(1f, cr - inset), paint);
            paint.setShader(null);

            // 6. Ilustración interior en coordenadas normalizadas (-50..+50) con sombra suave bajo el símbolo.
            float u = bw / 100f;
            cv.save();
            cv.translate(cx, cy);
            drawGlyph(cv, u, true);
            drawGlyph(cv, u, false);
            cv.restore();

            // 7. Punto de aviso en la esquina superior derecha de Coche cuando hay una alerta pendiente.
            if (alert && "car".equals(id)) {
                float ax = box.right - bw * 0.14f;
                float ay = box.top + bw * 0.14f;
                float ar = Math.max(3.8f, bw * 0.115f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                cv.drawCircle(ax, ay, ar + Math.max(1.4f, bw * 0.032f), paint);
                paint.setColor(0xFFFF3B30);
                cv.drawCircle(ax, ay, ar, paint);
            }

            cv.restoreToCount(saved);
        }

        /** Dibuja el símbolo de la ficha (shadow=true pinta una sombra suave desplazada debajo). */
        private void drawGlyph(Canvas cv, float u, boolean shadow) {
            if (shadow) {
                cv.save();
                cv.translate(0.5f * u, 2.2f * u);
            }
            switch (id) {
                case "aa":        drawAa(cv, u, shadow); break;
                case "car":       drawCar(cv, u, shadow); break;
                case "photos":    drawPhotos(cv, u, shadow); break;
                case "videos":    drawVideos(cv, u, shadow); break;
                case "web":       drawWeb(cv, u, shadow); break;
                case "tv":        drawTv(cv, u, shadow); break;
                case "radio":     drawRadio(cv, u, shadow); break;
                case "games":     drawGames(cv, u, shadow); break;
                case "home":      drawHome(cv, u, shadow); break;
                case "guide":     drawGuide(cv, u, shadow); break;
                case "karaoke":   drawKaraoke(cv, u, shadow); break;
                case "companion": drawCompanion(cv, u, shadow); break;
                case "split":     drawSplit(cv, u, shadow); break;
                case "swap":      drawSwap(cv, u, shadow); break;
                case "expand":    drawExpand(cv, u, shadow); break;
                default:          drawSettings(cv, u, shadow); break;
            }
            if (shadow) cv.restore();
        }

        private static final int SHADOW_COLOR = 0x38000000;

        private void fill(int color, boolean shadow) {
            glyphPaint.reset();
            glyphPaint.setAntiAlias(true);
            glyphPaint.setStyle(Paint.Style.FILL);
            glyphPaint.setColor(shadow ? SHADOW_COLOR : color);
        }

        private void stroke(int color, float width, boolean shadow) {
            glyphPaint.reset();
            glyphPaint.setAntiAlias(true);
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeWidth(width);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            glyphPaint.setStrokeJoin(Paint.Join.ROUND);
            glyphPaint.setColor(shadow ? SHADOW_COLOR : color);
        }

        // ------------------------------------------------------------------ 1. Android Auto (flecha facetada)

        private void drawAa(Canvas cv, float u, boolean shadow) {
            // Ala izquierda (blanco puro).
            path.rewind();
            path.moveTo(0, -31 * u);
            path.lineTo(-27 * u, 23 * u);
            path.lineTo(-16.5f * u, 28 * u);
            path.lineTo(0, 12.5f * u);
            path.close();
            fill(0xFFFFFFFF, shadow);
            glyphPaint.setPathEffect(cpe24);
            cv.drawPath(path, glyphPaint);

            // Ala derecha (azul hielo luminoso: efecto facetado 3D).
            path2.rewind();
            path2.moveTo(0, -31 * u);
            path2.lineTo(27 * u, 23 * u);
            path2.lineTo(16.5f * u, 28 * u);
            path2.lineTo(0, 12.5f * u);
            path2.close();
            fill(0xFFC9EEFF, shadow);
            glyphPaint.setPathEffect(cpe24);
            cv.drawPath(path2, glyphPaint);

            if (!shadow) {
                // Arista luminosa central.
                stroke(0x99FFFFFF, 1.8f * u, false);
                cv.drawLine(0, -28 * u, 0, 11 * u, glyphPaint);
            }
        }

        // ------------------------------------------------------------------ 2. Coche (velocímetro EV)

        private void drawCar(Canvas cv, float u, boolean shadow) {
            float cy = 4 * u;
            float r = 29 * u;
            tmp.set(-r, cy - r, r, cy + r);

            if (!shadow) {
                // Pista de fondo translúcida (250°).
                stroke(0x4AFFFFFF, 6.2f * u, false);
                cv.drawArc(tmp, 145f, 250f, false, glyphPaint);
            }

            // Arco activo iluminado.
            stroke(0xFFFFFFFF, 6.2f * u, shadow);
            cv.drawArc(tmp, 145f, 178f, false, glyphPaint);

            if (!shadow) {
                // Zona alta con matiz cálido.
                stroke(0xB8FFF1B8, 6.2f * u, false);
                cv.drawArc(tmp, 336f, 59f, false, glyphPaint);
            }

            // Aguja afilada apuntando arriba a la derecha.
            path.rewind();
            path.moveTo(-3.2f * u, cy - 1.5f * u);
            path.lineTo(19.5f * u, cy - 17.5f * u);
            path.lineTo(2.4f * u, cy + 3.2f * u);
            path.close();
            fill(0xFFFFFFFF, shadow);
            glyphPaint.setPathEffect(cpe14);
            cv.drawPath(path, glyphPaint);

            // Eje central.
            fill(0xFFFFFFFF, shadow);
            cv.drawCircle(0, cy, 6.2f * u, glyphPaint);
            if (!shadow) {
                fill(alert ? 0xFFEA580C : 0xFF047857, false);
                cv.drawCircle(0, cy, 2.7f * u, glyphPaint);
            }

            // Rayo EV debajo del eje.
            path2.rewind();
            path2.moveTo(1.5f * u, 16 * u);
            path2.lineTo(-5.5f * u, 25 * u);
            path2.lineTo(-0.5f * u, 25 * u);
            path2.lineTo(-2 * u, 33 * u);
            path2.lineTo(5.8f * u, 23 * u);
            path2.lineTo(0.8f * u, 23 * u);
            path2.close();
            fill(0xFFD6FFF6, shadow);
            cv.drawPath(path2, glyphPaint);
        }

        // ------------------------------------------------------------------ 3. Fotos (paisaje por capas)

        private void drawPhotos(Canvas cv, float u, boolean shadow) {
            tmp.set(-30 * u, -24 * u, 30 * u, 24 * u);
            float fr = 9 * u;
            if (shadow) {
                fill(SHADOW_COLOR, true);
                cv.drawRoundRect(tmp, fr, fr, glyphPaint);
                return;
            }
            // Marco interior ligeramente oscurecido para que destaquen el sol y la montaña nevada.
            fill(0x28541224, false);
            cv.drawRoundRect(tmp, fr, fr, glyphPaint);

            cv.save();
            path.rewind();
            path.addRoundRect(tmp, fr, fr, Path.Direction.CW);
            cv.clipPath(path);

            // Halo + disco solar.
            fill(0x50FFF5CC, false);
            cv.drawCircle(-13.5f * u, -9.5f * u, 9.5f * u, glyphPaint);
            fill(0xFFFFFBE6, false);
            cv.drawCircle(-13.5f * u, -9.5f * u, 5.8f * u, glyphPaint);

            // Montaña trasera (tono crema translúcido).
            path2.rewind();
            path2.moveTo(-4 * u, 25 * u);
            path2.lineTo(13 * u, -3.5f * u);
            path2.lineTo(32 * u, 25 * u);
            path2.close();
            fill(0xB8FFF0DC, false);
            glyphPaint.setPathEffect(cpe32);
            cv.drawPath(path2, glyphPaint);

            // Montaña delantera (blanco nieve).
            path2.rewind();
            path2.moveTo(-32 * u, 25 * u);
            path2.lineTo(-8 * u, -7.5f * u);
            path2.lineTo(17 * u, 25 * u);
            path2.close();
            fill(0xFFFFFFFF, false);
            glyphPaint.setPathEffect(cpe32);
            cv.drawPath(path2, glyphPaint);
            cv.restore();

            // Contorno nítido del marco.
            stroke(0xB8FFFFFF, 3.2f * u, false);
            cv.drawRoundRect(tmp, fr, fr, glyphPaint);
        }

        // ------------------------------------------------------------------ 4. Vídeos (medallón de cine)

        private void drawVideos(Canvas cv, float u, boolean shadow) {
            if (!shadow) {
                fill(0x22FFFFFF, false);
                cv.drawCircle(0, 0, 30 * u, glyphPaint);
                stroke(0x66FFFFFF, 2.8f * u, false);
                cv.drawCircle(0, 0, 30 * u, glyphPaint);
            }
            // Triángulo Play redondeado, centrado visualmente (+3u).
            path.rewind();
            path.moveTo(-10 * u, -17.5f * u);
            path.lineTo(20 * u, 0);
            path.lineTo(-10 * u, 17.5f * u);
            path.close();
            fill(0xFFFFFFFF, shadow);
            glyphPaint.setPathEffect(cpe46);
            cv.drawPath(path, glyphPaint);

            if (!shadow) {
                // Faceta inferior suave para dar volumen al Play.
                cv.save();
                cv.clipPath(path);
                fill(0x209F1239, false);
                cv.drawRect(-14 * u, 0, 24 * u, 20 * u, glyphPaint);
                cv.restore();
            }
        }

        // ------------------------------------------------------------------ 5. Web (globo 3D)

        private void drawWeb(Canvas cv, float u, boolean shadow) {
            float r = 28.5f * u;
            if (!shadow) {
                fill(0x20FFFFFF, false);
                cv.drawCircle(0, 0, r, glyphPaint);
            }
            stroke(0xFFFFFFFF, 4.4f * u, shadow);
            cv.drawCircle(0, 0, r, glyphPaint);

            // Meridiano central elíptico.
            stroke(0xE8FFFFFF, 3.6f * u, shadow);
            tmp.set(-13.5f * u, -r, 13.5f * u, r);
            cv.drawOval(tmp, glyphPaint);

            // Ecuador.
            cv.drawLine(-r + 1.5f * u, 0, r - 1.5f * u, 0, glyphPaint);

            if (!shadow) {
                // Paralelos curvos (sensación esférica).
                stroke(0xAAFFFFFF, 3.0f * u, false);
                path.rewind();
                path.moveTo(-22.5f * u, -14.5f * u);
                path.quadTo(0, -9.5f * u, 22.5f * u, -14.5f * u);
                cv.drawPath(path, glyphPaint);

                path2.rewind();
                path2.moveTo(-22.5f * u, 14.5f * u);
                path2.quadTo(0, 9.5f * u, 22.5f * u, 14.5f * u);
                cv.drawPath(path2, glyphPaint);
            }
        }

        // ------------------------------------------------------------------ 6. TV (Smart TV panorámica)

        private void drawTv(Canvas cv, float u, boolean shadow) {
            tmp.set(-30 * u, -24 * u, 30 * u, 15 * u);
            if (!shadow) {
                // Pantalla interior oscura de cristal + reflejo diagonal.
                fill(0x521E0A3C, false);
                cv.drawRoundRect(tmp, 8 * u, 8 * u, glyphPaint);
                cv.save();
                path.rewind();
                path.addRoundRect(tmp, 8 * u, 8 * u, Path.Direction.CW);
                cv.clipPath(path);
                path2.rewind();
                path2.moveTo(-30 * u, -24 * u);
                path2.lineTo(4 * u, -24 * u);
                path2.lineTo(-14 * u, 15 * u);
                path2.lineTo(-30 * u, 15 * u);
                path2.close();
                fill(0x22FFFFFF, false);
                cv.drawPath(path2, glyphPaint);
                cv.restore();
            }

            // Marco exterior nítido.
            stroke(0xFFFFFFFF, 4.6f * u, shadow);
            cv.drawRoundRect(tmp, 8 * u, 8 * u, glyphPaint);

            // Pequeño Play luminoso dentro de la pantalla.
            path.rewind();
            path.moveTo(-5.5f * u, -12.5f * u);
            path.lineTo(10.5f * u, -4.5f * u);
            path.lineTo(-5.5f * u, 3.5f * u);
            path.close();
            fill(0xFFFFFFFF, shadow);
            glyphPaint.setPathEffect(cpe24);
            cv.drawPath(path, glyphPaint);

            // Peana minimalista.
            stroke(0xE6FFFFFF, 4.4f * u, shadow);
            cv.drawLine(0, 16 * u, 0, 24.5f * u, glyphPaint);
            cv.drawLine(-14 * u, 25 * u, 14 * u, 25 * u, glyphPaint);
        }

        // ------------------------------------------------------------------ 7. Radio (ondas de emisión)

        private void drawRadio(Canvas cv, float u, boolean shadow) {
            float cy = -5 * u;
            if (!shadow) {
                fill(0x3CFFFFFF, false);
                cv.drawCircle(0, cy, 10.5f * u, glyphPaint);
            }
            // Núcleo luminoso.
            fill(0xFFFFFFFF, shadow);
            cv.drawCircle(0, cy, 6.0f * u, glyphPaint);

            // Torre / mástil estilizado.
            stroke(0xF0FFFFFF, 4.6f * u, shadow);
            cv.drawLine(0, cy + 6 * u, 0, 27 * u, glyphPaint);
            cv.drawLine(-10 * u, 27 * u, 10 * u, 27 * u, glyphPaint);

            // Ondas interiores (blancas intensas).
            stroke(0xFFFFFFFF, 4.4f * u, shadow);
            float r1 = 16.5f * u;
            tmp.set(-r1, cy - r1, r1, cy + r1);
            cv.drawArc(tmp, 142f, 76f, false, glyphPaint);
            cv.drawArc(tmp, -38f, 76f, false, glyphPaint);

            // Ondas exteriores (translúcidas: profundidad).
            stroke(0xA6FFFFFF, 4.2f * u, shadow);
            float r2 = 27.5f * u;
            tmp.set(-r2, cy - r2, r2, cy + r2);
            cv.drawArc(tmp, 136f, 88f, false, glyphPaint);
            cv.drawArc(tmp, -44f, 88f, false, glyphPaint);
        }

        // ------------------------------------------------------------------ 8. Juegos (mando ergonómico)

        private void drawGames(Canvas cv, float u, boolean shadow) {
            // Silueta ergonómica con agarres curvos.
            path.rewind();
            path.moveTo(-16 * u, -17 * u);
            path.lineTo(16 * u, -17 * u);
            path.cubicTo(27 * u, -17 * u, 33 * u, -6 * u, 32 * u, 12 * u);
            path.cubicTo(31.5f * u, 23 * u, 23 * u, 26 * u, 17 * u, 19 * u);
            path.lineTo(10.5f * u, 11.5f * u);
            path.lineTo(-10.5f * u, 11.5f * u);
            path.lineTo(-17 * u, 19 * u);
            path.cubicTo(-23 * u, 26 * u, -31.5f * u, 23 * u, -32 * u, 12 * u);
            path.cubicTo(-33 * u, -6 * u, -27 * u, -17 * u, -16 * u, -17 * u);
            path.close();

            fill(0xFFFFFFFF, shadow);
            cv.drawPath(path, glyphPaint);

            if (shadow) return;

            // Cruceta izquierda (talla violeta oscura).
            stroke(0xCC6D28D9, 4.4f * u, false);
            cv.drawLine(-15.5f * u, -9.5f * u, -15.5f * u, 3.5f * u, glyphPaint);
            cv.drawLine(-22 * u, -3 * u, -9 * u, -3 * u, glyphPaint);

            // Cuatro botones de colores a la derecha.
            float bx = 15.5f * u;
            float by = -3 * u;
            float bd = 6.2f * u;
            float br = 2.9f * u;
            fill(0xFFF59E0B, false); // arriba: ámbar
            cv.drawCircle(bx, by - bd, br, glyphPaint);
            fill(0xFFEC4899, false); // derecha: fucsia
            cv.drawCircle(bx + bd, by, br, glyphPaint);
            fill(0xFF06B6D4, false); // abajo: cian
            cv.drawCircle(bx, by + bd, br, glyphPaint);
            fill(0xFF10B981, false); // izquierda: verde
            cv.drawCircle(bx - bd, by, br, glyphPaint);
        }

        // ------------------------------------------------------------------ 9. Casa (Home Assistant)

        private void drawHome(Canvas cv, float u, boolean shadow) {
            // Silueta de casa con tejado a dos aguas y chimenea.
            path.rewind();
            path.moveTo(0, -29 * u);
            path.lineTo(12 * u, -18 * u);
            path.lineTo(12 * u, -24 * u);
            path.lineTo(19.5f * u, -24 * u);
            path.lineTo(19.5f * u, -11.5f * u);
            path.lineTo(29 * u, -2.5f * u);
            path.lineTo(22 * u, -2.5f * u);
            path.lineTo(22 * u, 25 * u);
            path.lineTo(-22 * u, 25 * u);
            path.lineTo(-22 * u, -2.5f * u);
            path.lineTo(-29 * u, -2.5f * u);
            path.close();

            fill(0xFFFFFFFF, shadow);
            glyphPaint.setPathEffect(cpe24);
            cv.drawPath(path, glyphPaint);

            if (shadow) return;

            // Árbol de nodos conectados dentro de la casa (seña de identidad de Home Assistant).
            int wire = 0xFF0369A1;
            stroke(wire, 3.6f * u, false);
            cv.drawLine(0, 25 * u, 0, -8 * u, glyphPaint);
            cv.drawLine(0, 14 * u, -11 * u, 4 * u, glyphPaint);
            cv.drawLine(0, 14 * u, 11 * u, 4 * u, glyphPaint);

            drawHaNode(cv, 0, -9 * u, u, wire);
            drawHaNode(cv, -11.5f * u, 3.5f * u, u, wire);
            drawHaNode(cv, 11.5f * u, 3.5f * u, u, wire);
        }

        private void drawHaNode(Canvas cv, float x, float y, float u, int wire) {
            fill(wire, false);
            cv.drawCircle(x, y, 4.4f * u, glyphPaint);
            fill(0xFFFFFFFF, false);
            cv.drawCircle(x, y, 1.8f * u, glyphPaint);
        }

        // ------------------------------------------------------------------ 10. Guía (brújula de precisión)

        private void drawGuide(Canvas cv, float u, boolean shadow) {
            float r = 28.5f * u;
            if (!shadow) {
                fill(0x24FFFFFF, false);
                cv.drawCircle(0, 0, r, glyphPaint);
            }
            stroke(0xFFFFFFFF, 4.4f * u, shadow);
            cv.drawCircle(0, 0, r, glyphPaint);

            if (!shadow) {
                // Cuatro marcas cardinales sutiles.
                stroke(0x88FFFFFF, 2.6f * u, false);
                cv.drawLine(0, -23.5f * u, 0, -19 * u, glyphPaint);
                cv.drawLine(0, 19 * u, 0, 23.5f * u, glyphPaint);
                cv.drawLine(-23.5f * u, 0, -19 * u, 0, glyphPaint);
                cv.drawLine(19 * u, 0, 23.5f * u, 0, glyphPaint);
            }

            // Aguja romboidal inclinada 45°: norte en rojo coral, sur en blanco.
            path.rewind();
            path.moveTo(16.5f * u, -16.5f * u);
            path.lineTo(-5.5f * u, -5.5f * u);
            path.lineTo(5.5f * u, 5.5f * u);
            path.close();
            fill(0xFFFF5252, shadow);
            glyphPaint.setPathEffect(cpe14);
            cv.drawPath(path, glyphPaint);

            path2.rewind();
            path2.moveTo(-16.5f * u, 16.5f * u);
            path2.lineTo(-5.5f * u, -5.5f * u);
            path2.lineTo(5.5f * u, 5.5f * u);
            path2.close();
            fill(0xFFFFFFFF, shadow);
            glyphPaint.setPathEffect(cpe14);
            cv.drawPath(path2, glyphPaint);

            if (!shadow) {
                fill(0xFF064E3B, false);
                cv.drawCircle(0, 0, 3.2f * u, glyphPaint);
                fill(0xFFFFFFFF, false);
                cv.drawCircle(0, 0, 1.4f * u, glyphPaint);
            }
        }

        // ------------------------------------------------------------------ 11. Karaoke (micrófono + nota)

        private void drawKaraoke(Canvas cv, float u, boolean shadow) {
            cv.save();
            cv.translate(-5 * u, 4 * u);
            cv.rotate(35f);

            // Mango estilizado.
            path.rewind();
            path.moveTo(-4.8f * u, 3 * u);
            path.lineTo(4.8f * u, 3 * u);
            path.lineTo(3.2f * u, 27 * u);
            path.lineTo(-3.2f * u, 27 * u);
            path.close();
            fill(0xE8F3E8FF, shadow);
            glyphPaint.setPathEffect(cpe24);
            cv.drawPath(path, glyphPaint);

            // Cápsula metálica.
            fill(0xFFFFFFFF, shadow);
            cv.drawCircle(0, -9 * u, 11.5f * u, glyphPaint);
            if (!shadow) {
                stroke(0xCC9333EA, 2.4f * u, false);
                cv.drawLine(-10.5f * u, -9 * u, 10.5f * u, -9 * u, glyphPaint);
                stroke(0x457E22CE, 1.8f * u, false);
                cv.drawLine(-8 * u, -14.5f * u, 8 * u, -14.5f * u, glyphPaint);
            }
            cv.restore();

            // Corchea flotante arriba a la derecha.
            fill(0xFFFFE4F6, shadow);
            cv.drawCircle(16 * u, -9 * u, 4.6f * u, glyphPaint);
            stroke(0xFFFFFFFF, 3.4f * u, shadow);
            cv.drawLine(19.5f * u, -9.5f * u, 19.5f * u, -25.5f * u, glyphPaint);
            cv.drawLine(19.5f * u, -25.5f * u, 27 * u, -21.5f * u, glyphPaint);
        }

        // ------------------------------------------------------------------ 12. Compañero (ondas IA + destello)

        private void drawCompanion(Canvas cv, float u, boolean shadow) {
            // Cinco barras simétricas de onda de voz.
            float[] xs = {-22f, -12f, -2f, 8f, 18f};
            float[] hs = {7f, 14.5f, 22f, 13.5f, 7f};
            float cy = 4 * u;
            for (int i = 0; i < xs.length; i++) {
                int col = (i == 0 || i == 4) ? 0xB8FFFFFF : 0xFFFFFFFF;
                stroke(col, 5.2f * u, shadow);
                cv.drawLine(xs[i] * u, cy - hs[i] * u, xs[i] * u, cy + hs[i] * u, glyphPaint);
            }

            // Destello de 4 puntas (IA) arriba a la derecha.
            float sx = 20.5f * u;
            float sy = -20.5f * u;
            float sr = 10.5f * u;
            path.rewind();
            path.moveTo(sx, sy - sr);
            path.quadTo(sx, sy, sx + sr, sy);
            path.quadTo(sx, sy, sx, sy + sr);
            path.quadTo(sx, sy, sx - sr, sy);
            path.quadTo(sx, sy, sx, sy - sr);
            path.close();
            fill(0xFFFFF9C4, shadow);
            cv.drawPath(path, glyphPaint);
        }

        // ------------------------------------------------------------------ 13. Partir pantalla (dos paneles)

        private void drawSplit(Canvas cv, float u, boolean shadow) {
            // Panel izquierdo sólido (AA).
            tmp.set(-29 * u, -22 * u, -3.5f * u, 22 * u);
            fill(0xFFFFFFFF, shadow);
            cv.drawRoundRect(tmp, 6.5f * u, 6.5f * u, glyphPaint);
            if (!shadow) {
                fill(0x300369A1, false);
                tmp.set(-24.5f * u, -14 * u, -8 * u, -8 * u);
                cv.drawRoundRect(tmp, 2.5f * u, 2.5f * u, glyphPaint);
            }

            // Panel derecho en cristal translúcido con borde nítido.
            tmp.set(3.5f * u, -22 * u, 29 * u, 22 * u);
            if (!shadow) {
                fill(0x42FFFFFF, false);
                cv.drawRoundRect(tmp, 6.5f * u, 6.5f * u, glyphPaint);
            }
            stroke(0xF0FFFFFF, 3.8f * u, shadow);
            cv.drawRoundRect(tmp, 6.5f * u, 6.5f * u, glyphPaint);
        }

        // ------------------------------------------------------------------ 14. Cambiar de lado (⇄)

        private void drawSwap(Canvas cv, float u, boolean shadow) {
            // Flecha superior hacia la derecha.
            stroke(0xFFFFFFFF, 5.4f * u, shadow);
            cv.drawLine(-24 * u, -10.5f * u, 21 * u, -10.5f * u, glyphPaint);
            path.rewind();
            path.moveTo(11.5f * u, -20 * u);
            path.lineTo(22.5f * u, -10.5f * u);
            path.lineTo(11.5f * u, -1 * u);
            cv.drawPath(path, glyphPaint);

            // Flecha inferior hacia la izquierda (matiz hielo suave).
            stroke(0xC8ECFEFF, 5.4f * u, shadow);
            cv.drawLine(24 * u, 10.5f * u, -21 * u, 10.5f * u, glyphPaint);
            path2.rewind();
            path2.moveTo(-11.5f * u, 1 * u);
            path2.lineTo(-22.5f * u, 10.5f * u);
            path2.lineTo(-11.5f * u, 20 * u);
            cv.drawPath(path2, glyphPaint);
        }

        // ------------------------------------------------------------------ 15. Ajustes (engranaje mecanizado)

        private void drawSettings(Canvas cv, float u, boolean shadow) {
            int teeth = 8;
            float rOut = 29.5f * u;
            float rIn = 21.5f * u;
            path.rewind();
            for (int i = 0; i < teeth; i++) {
                double base = i * (2.0 * Math.PI / teeth);
                double a1 = base - 0.24;
                double a2 = base - 0.14;
                double a3 = base + 0.14;
                double a4 = base + 0.24;
                float x1 = (float) (Math.cos(a1) * rIn);
                float y1 = (float) (Math.sin(a1) * rIn);
                float x2 = (float) (Math.cos(a2) * rOut);
                float y2 = (float) (Math.sin(a2) * rOut);
                float x3 = (float) (Math.cos(a3) * rOut);
                float y3 = (float) (Math.sin(a3) * rOut);
                float x4 = (float) (Math.cos(a4) * rIn);
                float y4 = (float) (Math.sin(a4) * rIn);
                if (i == 0) path.moveTo(x1, y1);
                else path.lineTo(x1, y1);
                path.lineTo(x2, y2);
                path.lineTo(x3, y3);
                path.lineTo(x4, y4);
            }
            path.close();

            fill(0xFFFFFFFF, shadow);
            if (!shadow && gearShader != null) {
                glyphPaint.setShader(gearShader);
            }
            glyphPaint.setPathEffect(cpe24);
            cv.drawPath(path, glyphPaint);
            glyphPaint.setShader(null);

            if (shadow) return;

            // Aro interior biselado y hueco central mecanizado.
            stroke(0x380F172A, 2.2f * u, false);
            cv.drawCircle(0, 0, 14.5f * u, glyphPaint);
            fill(0xFF283548, false);
            cv.drawCircle(0, 0, 9.8f * u, glyphPaint);
            stroke(0x70FFFFFF, 1.8f * u, false);
            cv.drawCircle(0, 0, 9.8f * u, glyphPaint);
        }

        // ------------------------------------------------------------------ 16. Desplegar panel (»)

        private void drawExpand(Canvas cv, float u, boolean shadow) {
            int fg = darkSurface ? 0xFFFFFFFF : 0xFF1E293B;
            int dim = darkSurface ? 0x80FFFFFF : 0x661E293B;
            stroke(dim, 6.2f * u, shadow);
            path.rewind();
            path.moveTo(-14 * u, -15 * u);
            path.lineTo(-1 * u, 0);
            path.lineTo(-14 * u, 15 * u);
            cv.drawPath(path, glyphPaint);

            stroke(fg, 6.2f * u, shadow);
            path2.rewind();
            path2.moveTo(2 * u, -15 * u);
            path2.lineTo(15 * u, 0);
            path2.lineTo(2 * u, 15 * u);
            cv.drawPath(path2, glyphPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Ficha de cristal sutil para los accesos fijados de Home Assistant en la barra. */
    private static final class HaBadge extends Drawable {
        private final boolean dark;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private Shader fillShader;

        HaBadge(boolean dark) {
            this.dark = dark;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            fillShader = null;
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float s = Math.min(b.width(), b.height());
            float half = s * 0.45f;
            box.set(b.exactCenterX() - half, b.exactCenterY() - half, b.exactCenterX() + half, b.exactCenterY() + half);
            float cr = box.width() * 0.28f;
            if (fillShader == null) {
                fillShader = new LinearGradient(box.left, box.top, box.right, box.bottom,
                        dark ? 0x4429B6F6 : 0x330284C7, dark ? 0x260369A1 : 0x1E0369A1, Shader.TileMode.CLAMP);
            }
            p.setColor(0xFFFFFFFF);
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            p.setShader(fillShader);
            cv.drawRoundRect(box, cr, cr, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.2f, s * 0.028f));
            p.setColor(dark ? 0x557DD3FC : 0x660284C7);
            cv.drawRoundRect(box, cr, cr, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Píldora activa de cristal tintado con borde luminoso y barra indicadora exterior. */
    private static final class ActivePill extends Drawable {
        private final boolean dark;
        private final boolean panelRight;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final RectF bar = new RectF();
        private Shader bgShader;
        private Shader barShader;

        ActivePill(boolean dark, boolean panelRight) {
            this.dark = dark;
            this.panelRight = panelRight;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            bgShader = null;
            barShader = null;
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) return;
            float cr = Math.min(22f, Math.min(w, h) * 0.32f);
            float strokeW = 1.6f;
            r.set(b.left + strokeW * 0.5f, b.top + strokeW * 0.5f, b.right - strokeW * 0.5f, b.bottom - strokeW * 0.5f);

            if (bgShader == null) {
                bgShader = dark
                        ? new LinearGradient(b.left, b.top, b.right, b.bottom, 0x3D00E5C7, 0x1A4CFF9F, Shader.TileMode.CLAMP)
                        : new LinearGradient(b.left, b.top, b.right, b.bottom, 0xF2FFFFFF, 0xE6DDF4F0, Shader.TileMode.CLAMP);
                barShader = dark
                        ? new LinearGradient(0, b.top, 0, b.bottom, 0xFF00E5C7, 0xFF4CFF9F, Shader.TileMode.CLAMP)
                        : new LinearGradient(0, b.top, 0, b.bottom, 0xFF00897B, 0xFF15A05A, Shader.TileMode.CLAMP);
            }

            // Base suave de cristal en panel oscuro para dar cuerpo sobre negro puro.
            p.setStyle(Paint.Style.FILL);
            if (dark) {
                p.setShader(null);
                p.setColor(0x16FFFFFF);
                cv.drawRoundRect(r, cr, cr, p);
            }
            p.setColor(0xFFFFFFFF);
            p.setAlpha(255);
            p.setShader(bgShader);
            cv.drawRoundRect(r, cr, cr, p);

            // Borde luminoso del acento.
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            p.setColor(dark ? 0xB300E5C7 : 0xB300897B);
            cv.drawRoundRect(r, cr, cr, p);

            // En el panel desplegado (ancho > 110), barra indicadora vertical junto al borde exterior;
            // en la barra de iconos (cuadrada), el marco luminoso rodea la ficha de forma simétrica.
            if (w > 110) {
                float bw = 4.2f;
                float bh = h * 0.46f;
                float by = b.exactCenterY() - bh * 0.5f;
                float bx = panelRight ? b.right - bw - 6f : b.left + 6f;
                bar.set(bx, by, bx + bw, by + bh);
                p.setColor(0xFFFFFFFF);
                p.setAlpha(255);
                p.setStyle(Paint.Style.FILL);
                p.setShader(barShader);
                cv.drawRoundRect(bar, bw * 0.5f, bw * 0.5f, p);
                p.setShader(null);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
