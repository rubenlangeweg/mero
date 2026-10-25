package studio.invrse.mero;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Sony's prioritized system home can override the user's chosen home app. */
public final class HomeRedirectService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String name = String.valueOf(event.getClassName());
        if (name.equals("com.google.android.apps.tv.launcherx.home.HomeActivity")
                || name.equals("com.google.android.tungsten.setupwraith.RecoveryActivity")) {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
    }

    @Override public void onInterrupt() { }
}
