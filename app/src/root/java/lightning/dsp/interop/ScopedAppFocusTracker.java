package lightning.dsp.interop;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewTreeObserver;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Only tracks window focus in this explicitly scoped app. Never reads another app's UI. */
final class ScopedAppFocusTracker implements Application.ActivityLifecycleCallbacks {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, ViewTreeObserver.OnWindowFocusChangeListener> activities = new IdentityHashMap<>();
    private final BooleanSupplier enabled;
    private final Consumer<Boolean> report;
    private boolean wasFocused;
    private final Runnable refresh = this::safeRefresh;

    ScopedAppFocusTracker(Application app, BooleanSupplier enabled, Consumer<Boolean> report) {
        this.enabled = enabled;
        this.report = report;
        app.registerActivityLifecycleCallbacks(this);
    }

    void refresh() { main.removeCallbacks(refresh); main.post(refresh); }

    private void safeRefresh() {
        try { refreshOnMain(); }
        catch (Throwable unavailable) {
            main.removeCallbacks(refresh);
            wasFocused = false; // A lost focus lease fails closed at the provider.
        }
    }

    private void refreshOnMain() {
        main.removeCallbacks(refresh);
        boolean focused = enabled.getAsBoolean() && activities.keySet().stream().anyMatch(activity ->
                !activity.isDestroyed() && !activity.isFinishing() && activity.hasWindowFocus());
        if (focused || wasFocused) report.accept(focused);
        wasFocused = focused;
        // A missed loss report expires at the provider; no periodic work when unfocused.
        if (focused) main.postDelayed(refresh, 2_000L);
    }

    private void observe(Activity activity) {
        try {
            if (!activities.containsKey(activity)) {
                if (activities.size() >= 64) return;
                ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
                if (!observer.isAlive()) return;
                ViewTreeObserver.OnWindowFocusChangeListener listener = ignored -> safeRefresh();
                activities.put(activity, listener);
                observer.addOnWindowFocusChangeListener(listener);
            }
            safeRefresh();
        } catch (Throwable unavailable) { safeRefresh(); }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { observe(activity); }
    @Override public void onActivityResumed(Activity activity) { observe(activity); }
    @Override public void onActivityPaused(Activity activity) { safeRefresh(); }
    @Override public void onActivityStopped(Activity activity) { safeRefresh(); }
    @Override public void onActivityDestroyed(Activity activity) {
        ViewTreeObserver.OnWindowFocusChangeListener listener = activities.remove(activity);
        try {
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (listener != null && observer.isAlive()) observer.removeOnWindowFocusChangeListener(listener);
        } catch (Throwable ignored) { /* Host lifecycle must not fail because of our observer. */ }
        safeRefresh();
    }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
