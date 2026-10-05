package com.example.statusbarseconds;

import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "StatusBarSeconds";
    private static final WeakHashMap<Object, Boolean> TICKING = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;

        if (!"com.android.systemui".equals(pkg)
                && !"com.miui.systemui".equals(pkg)
                && !"com.miui.systemui.plugin".equals(pkg)) {
            return;
        }

        XposedBridge.log(TAG + ": loaded in " + pkg);

        // === 1. hook 已知的 Clock 类 ===
        String[] clockClasses = new String[]{
                "com.android.systemui.statusbar.policy.Clock",
                "com.android.systemui.statusbar.phone.Clock",
                "com.android.systemui.statusbar.phone.MiuiClock",
                "com.android.systemui.statusbar.policy.MiuiClock",
                "miui.systemui.statusbar.phone.MiuiClock",
                "com.miui.systemui.statusbar.phone.MiuiClock"
        };

        for (String className : clockClasses) {
            hookClock(lpparam, className);
        }

        // === 2. 通用 hook：TextView.setText，只改时钟，不改日期 ===
        try {
            XposedHelpers.findAndHookMethod(
                    TextView.class,
                    "setText",
                    CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object target = param.thisObject;
                            if (!(target instanceof View)) return;

                            View view = (View) target;
                            String cls = view.getClass().getName();

                            if (!cls.contains("Clock") && !cls.contains("MiuiClock")) {
                                return;
                            }

                            // 日期控件不处理
                            if (isDateView(view)) {
                                return;
                            }

                            // 锁屏/息屏不处理
                            if (isKeyguardOrAodView(view)) {
                                return;
                            }

                            CharSequence old = (CharSequence) param.args[0];
                            CharSequence now = formatNow();
                            XposedBridge.log(TAG + ": Clock setText old=" + old + " new=" + now);
                            param.args[0] = now;
                        }
                    }
            );
            XposedBridge.log(TAG + ": TextView.setText global hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TextView.setText hook failed: " + t);
        }

        // === 3. 给所有时钟控件启动秒级刷新 ===
        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object target = param.thisObject;
                            if (!(target instanceof View)) return;

                            View view = (View) target;
                            String cls = view.getClass().getName();

                            if (!cls.contains("Clock") && !cls.contains("MiuiClock")) {
                                return;
                            }

                            if (isDateView(view)) return;
                            if (isKeyguardOrAodView(view)) return;

                            XposedBridge.log(TAG + ": Clock attached " + cls);
                            startTicker(target);
                        }
                    }
            );
            XposedBridge.log(TAG + ": View.onAttachedToWindow global hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": View.onAttachedToWindow hook failed: " + t);
        }
    }

    private void hookClock(XC_LoadPackage.LoadPackageParam lpparam, String className) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
            XposedBridge.log(TAG + ": found " + className);

            XposedBridge.hookAllMethods(clazz, "getSmallTime", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object clock = param.thisObject;
                    if (clock instanceof View) {
                        View v = (View) clock;
                        if (isDateView(v) || isKeyguardOrAodView(v)) return;
                    }
                    CharSequence old = (CharSequence) param.getResult();
                    CharSequence now = formatNow();
                    XposedBridge.log(TAG + ": getSmallTime old=" + old + " new=" + now);
                    param.setResult(now);
                }
            });

            XposedBridge.hookAllMethods(clazz, "updateClock", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object clock = param.thisObject;
                    if (clock instanceof View) {
                        View v = (View) clock;
                        if (isDateView(v) || isKeyguardOrAodView(v)) return;
                    }
                    startTicker(clock);
                    try {
                        XposedHelpers.callMethod(clock, "setText", formatNow());
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.hookAllMethods(clazz, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object clock = param.thisObject;
                    if (clock instanceof View) {
                        View v = (View) clock;
                        if (isDateView(v) || isKeyguardOrAodView(v)) return;
                    }
                    startTicker(clock);
                }
            });

        } catch (Throwable ignored) {
            // 类不存在，忽略
        }
    }

    /**
     * 判断是不是日期控件，避免把日期改成时间
     */
    private static boolean isDateView(View view) {
        if (view == null) return false;
        int id = view.getId();
        if (id == View.NO_ID) return false;
        try {
            String idName = view.getResources().getResourceName(id);
            if (idName == null) return false;
            if (idName.contains("date")) return true;

            // 检查父布局
            ViewParent parent = view.getParent();
            while (parent != null) {
                if (parent instanceof View) {
                    View p = (View) parent;
                    int pid = p.getId();
                    if (pid != View.NO_ID) {
                        String pName = p.getResources().getResourceName(pid);
                        if (pName != null && pName.contains("date")) {
                            return true;
                        }
                    }
                }
                parent = parent.getParent();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 排除锁屏和息屏显示
     */
    private static boolean isKeyguardOrAodView(View view) {
        if (view == null) return false;
        int id = view.getId();
        if (id == View.NO_ID) return false;
        try {
            String idName = view.getResources().getResourceName(id);
            if (idName == null) return false;
            if (idName.contains("keyguard") || idName.contains("aod")) return true;

            ViewParent parent = view.getParent();
            while (parent != null) {
                if (parent instanceof View) {
                    View p = (View) parent;
                    int pid = p.getId();
                    if (pid != View.NO_ID) {
                        String pName = p.getResources().getResourceName(pid);
                        if (pName != null && (pName.contains("keyguard") || pName.contains("aod"))) {
                            return true;
                        }
                    }
                }
                parent = parent.getParent();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void startTicker(final Object clock) {
        if (!(clock instanceof View)) return;

        synchronized (TICKING) {
            if (TICKING.containsKey(clock)) return;
            TICKING.put(clock, Boolean.TRUE);
        }

        final View view = (View) clock;

        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!view.isAttachedToWindow()) {
                    synchronized (TICKING) {
                        TICKING.remove(clock);
                    }
                    return;
                }

                try {
                    if (view instanceof TextView) {
                        ((TextView) view).setText(formatNow());
                    } else {
                        XposedHelpers.callMethod(clock, "setText", formatNow());
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": ticker setText failed: " + t);
                }

                view.postDelayed(this, 1000L);
            }
        };

        view.post(tick);
        XposedBridge.log(TAG + ": ticker started on " + clock.getClass().getName());
    }

    private static CharSequence formatNow() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }
}