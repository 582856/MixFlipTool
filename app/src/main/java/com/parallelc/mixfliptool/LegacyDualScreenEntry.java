package com.parallelc.mixfliptool;

import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LegacyDualScreenEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (("android".equals(param.packageName) || "system".equals(param.packageName))
                && ("android".equals(param.processName) || "system".equals(param.processName))) {
            try {
                OuterDisplayPowerHooks.install(param.classLoader, (method, after, callback) ->
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!after) invoke(p);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (after && !p.hasThrowable()) invoke(p);
                        }
                        private void invoke(MethodHookParam p) {
                            try { callback.run(p.thisObject, p.args); }
                            catch (Throwable error) { android.util.Log.e("MixFlipPower", "System hook failed", error); }
                        }
                    }));
                java.lang.reflect.Method inputPolicy = OuterDisplayPowerHooks.inputPolicyMethod(param.classLoader);
                if (inputPolicy != null) XposedBridge.hookMethod(inputPolicy, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (OuterDisplayPowerHooks.allowOuterInput((int) p.args[0])) p.setResult(true);
                    }
                });
                java.lang.reflect.Method wakeKey = OuterDisplayPowerHooks.wakeKeyMethod(param.classLoader);
                if (wakeKey != null) XposedBridge.hookMethod(wakeKey, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (OuterDisplayPowerHooks.handleOuterWakeKey((android.view.KeyEvent) p.args[0], (int) p.args[1])) p.setResult(0);
                    }
                });
            } catch (Throwable error) { android.util.Log.e("MixFlipPower", "System hook installation failed", error); }
            return;
        }
        if (!"com.android.systemui".equals(param.packageName) || !"com.android.systemui".equals(param.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                DualScreenModule.install((Context) param.args[0]);
            }
        });
    }
}
