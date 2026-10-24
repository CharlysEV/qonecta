package com.headqlink.link;

import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

/**
 * Lo que el bloqueo por dominio (AdBlock) no alcanza, como hacen uBlock Origin y compañía, en un guion que corre en cada
 * página (y en sus marcos, como el reproductor de YouTube incrustado de la TV) antes que la propia página:
 * - En cualquier web: esconde los huecos típicos de anuncios (AdSense, Google Publisher Tag, Taboola, Outbrain).
 * - En YouTube:
 *   1. quita los anuncios de los datos del reproductor (adPlacements, playerAds, adSlots) al leerlos (JSON.parse,
 *      Response.json y ytInitialPlayerResponse), así que ni se piden;
 *   2. si aun así sale uno, lo silencia, lo adelanta al final y pulsa «Saltar»; al acabar, vuelve el sonido;
 *   3. esconde los vídeos promocionados, los banners y los recuadros encima del vídeo.
 * YouTube cambia a menudo para esquivar esto: si un día vuelven los anuncios, se retocan las claves y los selectores.
 */
final class AdBlockJs {
    private AdBlockJs() {
    }

    /** Huecos de anuncios de cualquier web (pocos y seguros: no deben esconder contenido). */
    static final String GENERIC_CSS =
            "ins.adsbygoogle,.adsbygoogle,[id^=\"google_ads_iframe\"],[id^=\"div-gpt-ad\"],[data-google-query-id],"
                    + "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication.com\"],[id^=\"taboola-\"],"
                    + ".trc_related_container,.OUTBRAIN,.ob-widget{display:none!important}";

    /** YouTube (móvil, escritorio e incrustado): promocionados, banners y recuadros sobre el vídeo. */
    static final String YOUTUBE_CSS =
            "ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-ad-slot-renderer,"
                    + "ad-slot-renderer,ytd-ad-slot-renderer,ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,"
                    + "ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,ytm-statement-banner-renderer,"
                    + "ytd-statement-banner-renderer,ytm-paid-content-overlay-renderer,#player-ads,#masthead-ad,"
                    + ".ytp-ad-overlay-container,.ytp-ad-image-overlay,.ytp-ad-text-overlay{display:none!important}";

    /** El guion entero (una vez por página; con la marca __hqlAd no se repite). */
    static final String SCRIPT = "(function(){"
            + "if(window.__hqlAd)return;window.__hqlAd=1;"
            + "var yt=/(^|\\.)youtube(-nocookie)?\\.com$/.test(location.hostname);"
            + "var css=" + js(GENERIC_CSS) + "+(yt?" + js(YOUTUBE_CSS) + ":'');"
            + "function addCss(){try{var s=document.createElement('style');s.textContent=css;"
            + "(document.head||document.documentElement).appendChild(s);}catch(e){}}"
            + "if(document.documentElement)addCss();else document.addEventListener('DOMContentLoaded',addCss);"
            + "if(!yt)return;"
            // 1. Fuera los anuncios de los datos del reproductor.
            + "var K=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];"
            + "function clean(o){if(!o||typeof o!=='object')return;for(var i=0;i<K.length;i++){if(K[i] in o)try{delete o[K[i]];}catch(e){}}}"
            + "function prune(o){try{if(Array.isArray(o)){for(var j=0;j<o.length;j++){clean(o[j]);if(o[j])clean(o[j].playerResponse);}}"
            + "else if(o&&typeof o==='object'){clean(o);clean(o.playerResponse);}}catch(e){}return o;}"
            + "var P=JSON.parse;JSON.parse=function(){return prune(P.apply(this,arguments));};"
            + "if(window.Response&&Response.prototype.json){var J=Response.prototype.json;"
            + "Response.prototype.json=function(){return J.apply(this,arguments).then(prune);};}"
            + "try{var ip;Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,"
            + "get:function(){return ip;},set:function(v){ip=prune(v);}});}catch(e){}"
            // 2. Si cuela uno: silencio, al final y «Saltar»; al acabar, el sonido de antes.
            + "var muted=false;"
            + "setInterval(function(){try{"
            + "var ad=document.querySelector('.ad-showing,.ad-interrupting');var v=document.querySelector('video');"
            + "if(ad&&v){if(!muted){muted=true;v.muted=true;}"
            + "if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.3)v.currentTime=v.duration-0.1;}"
            + "else if(muted&&v){muted=false;v.muted=false;}"
            + "var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,"
            + ".ytp-ad-skip-button-slot button,.ytp-ad-overlay-close-button');if(b)b.click();"
            + "}catch(e){}},400);"
            + "})();";

    /** Puro: el texto como cadena de JavaScript entre comillas (los CSS llevan comillas dobles). */
    static String js(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** ¿El WebView sabe correr el guion antes que la página (y en los marcos)? Las versiones viejas, no. */
    static boolean early() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
    }

    /**
     * Lo pone en w antes que cada página, en todas las webs y sus marcos. Devuelve con qué quitarlo, o null si el
     * WebView no sabe (entonces, AdBlockJs.late en onPageStarted).
     */
    static ScriptHandler install(WebView w) {
        if (!early()) return null;
        try {
            return WebViewCompat.addDocumentStartJavaScript(w, SCRIPT, Collections.singleton("*"));
        } catch (RuntimeException e) {
            L.w("anuncios: el guion no se pudo poner (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /** WebView viejo: el guion al empezar la página (llega tarde a los datos del reproductor, pero salta y esconde). */
    static void late(WebView w) {
        w.evaluateJavascript(SCRIPT, null);
    }
}
