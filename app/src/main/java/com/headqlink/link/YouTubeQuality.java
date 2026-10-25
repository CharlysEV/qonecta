package com.headqlink.link;

import java.util.Locale;

/**
 * Calidad fija de YouTube en la pantalla Web (pedido en el foro alemán, 25-10): con la automática, YouTube sube hasta
 * 1080p o más, gasta datos y va a tirones. Un guion en las páginas de YouTube guarda la calidad preferida (como hace
 * YouTube en «yt-player-quality») y, cada 2 s, se la pone al reproductor si tiene otra: así vale también al pasar de un
 * vídeo a otro sin recargar la página. 0 = la automática de YouTube (no se toca nada).
 */
final class YouTubeQuality {
    /** Las que se pueden elegir, en el orden del menú. */
    static final int[] CHOICES = {0, 360, 480, 720};

    private YouTubeQuality() {
    }

    /** Puro: la siguiente del menú (automática → 360 → 480 → 720 → automática). */
    static int next(int q) {
        for (int i = 0; i < CHOICES.length; i++) if (CHOICES[i] == q) return CHOICES[(i + 1) % CHOICES.length];
        return CHOICES[0];
    }

    /** Puro: ¿una página de YouTube? (youtube.com, m.youtube.com, music.youtube.com…). */
    static boolean isYouTube(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.US);
        return h.equals("youtube.com") || h.endsWith(".youtube.com") || h.equals("youtu.be");
    }

    /** Puro: el nombre de la calidad para el reproductor de YouTube («medium» = 360p…). */
    static String level(int q) {
        if (q >= 720) return "hd720";
        if (q >= 480) return "large";
        if (q >= 360) return "medium";
        return "small";
    }

    /** Puro: el guion para la página (con q = 0 deja de forzar). Se puede volver a poner: cambia la calidad y no duplica. */
    static String js(int q) {
        return "(function(q){window.__hqlYtq=q;if(window.__hqlYtqT)return;"
                + "function lv(n){return n>=720?'hd720':n>=480?'large':n>=360?'medium':'small';}"
                + "function ap(){var t=window.__hqlYtq;if(!t)return;"
                + "try{var n=Date.now();localStorage.setItem('yt-player-quality',JSON.stringify({data:JSON.stringify("
                + "{quality:t,previousQuality:t}),expiration:n+31536000000,creation:n}));}catch(e){}"
                + "var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');"
                + "if(!p||!p.setPlaybackQualityRange)return;var l=lv(t);"
                + "try{if(p.getPlaybackQuality&&p.getPlaybackQuality()==l)return;p.setPlaybackQualityRange(l,l);"
                + "if(p.setPlaybackQuality)p.setPlaybackQuality(l);}catch(e){}}"
                + "window.__hqlYtqT=setInterval(ap,2000);ap();})(" + Math.max(0, q) + ")";
    }
}
