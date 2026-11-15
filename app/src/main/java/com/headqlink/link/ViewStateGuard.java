package com.headqlink.link;

import android.os.Bundle;

/**
 * Pantallas con vistas creadas en código e ids de View.generateViewId(): esos ids vuelven a empezar en cada proceso. Si
 * Android mata la app y la recrea con el estado guardado, el id de un botón del proceso anterior puede ser ahora el de
 * un interruptor, y al restaurar su estado Android revienta (10-11, Motorola edge 60 con 0.3.17: ClassCastException
 * «AbsSavedState$1 cannot be cast to CompoundButton$SavedState» en HomeActivity). El estado de las vistas solo se
 * restaura si lo guardó este mismo proceso (girar la pantalla, por ejemplo); el de cada pantalla (sus campos), siempre.
 */
final class ViewStateGuard {
    /** La clave con que Activity guarda el estado de las vistas (Activity.WINDOW_HIERARCHY_TAG). */
    static final String VIEWS = "android:viewHierarchyState";
    static final String PID = "hql_state_pid";

    private ViewStateGuard() {
    }

    /** En onSaveInstanceState: qué proceso lo guarda. */
    static void mark(Bundle out) {
        if (out != null) out.putInt(PID, android.os.Process.myPid());
    }

    /** En onRestoreInstanceState: sin el estado de las vistas si lo guardó otro proceso. */
    static Bundle strip(Bundle b) {
        if (b != null && otherProcess(b.getInt(PID, -1), android.os.Process.myPid())) {
            b.remove(VIEWS);
            L.i("estado guardado por otro proceso: no restauro el de las vistas (sus ids ya no casan)");
        }
        return b;
    }

    /** Puro: el estado lo guardó otro proceso (o una versión sin la marca). */
    static boolean otherProcess(int savedPid, int pid) {
        return savedPid != pid;
    }
}
