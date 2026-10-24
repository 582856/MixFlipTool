package com.parallelc.mixfliptool;

import android.app.Application;
import android.content.Context;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class DualScreenModuleEntry extends XposedModule {
    private boolean systemUi;

    @Override public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            OuterDisplayPowerHooks.install(param.getClassLoader(), (method, after, callback) ->
                hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object[] args = new Object[method.getParameterCount()];
                    for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
                    if (!after) {
                        try { callback.run(chain.getThisObject(), args); }
                        catch (Throwable error) { android.util.Log.e("MixFlipPower", "System hook failed", error); }
                    }
                    Object result = chain.proceed(args);
                    if (after) {
                        try { callback.run(chain.getThisObject(), args); }
                        catch (Throwable error) { android.util.Log.e("MixFlipPower", "System hook failed", error); }
                    }
                    return result;
                }));
            java.lang.reflect.Method inputPolicy = OuterDisplayPowerHooks.inputPolicyMethod(param.getClassLoader());
            if (inputPolicy != null) hook(inputPolicy)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> OuterDisplayPowerHooks.allowOuterInput((int) chain.getArg(0))
                    ? true : chain.proceed());
            java.lang.reflect.Method wakeKey = OuterDisplayPowerHooks.wakeKeyMethod(param.getClassLoader());
            if (wakeKey != null) hook(wakeKey)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> OuterDisplayPowerHooks.handleOuterWakeKey(
                    (android.view.KeyEvent) chain.getArg(0), (int) chain.getArg(1)) ? 0 : chain.proceed());
        } catch (Throwable error) { android.util.Log.e("MixFlipPower", "System hook installation failed", error); }
    }

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        systemUi = "com.android.systemui".equals(param.getProcessName());
    }

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!systemUi || !param.isFirstPackage() || !"com.android.systemui".equals(param.getPackageName())) return;
        try {
            hook(Application.class.getDeclaredMethod("attach", Context.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    DualScreenModule.install((Context) chain.getArg(0));
                    return result;
                });
        } catch (Throwable error) {
            android.util.Log.e("MixFlipModule", "Unable to attach", error);
        }
    }
}
