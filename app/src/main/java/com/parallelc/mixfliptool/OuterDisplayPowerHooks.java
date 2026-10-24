package com.parallelc.mixfliptool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class OuterDisplayPowerHooks {
    static final String ACTION = "com.parallelc.mixfliptool.OUTER_POWER";
    static final int PROTOCOL = 2;
    static final int PENDING = 3;
    static final String GESTURE_ACTION = "com.parallelc.mixfliptool.OUTER_GESTURE";
    private static final String TAG = "MixFlipPower";
    private static final String GROUP = "MixFlipTool.outer";
    private static final String OUTER = "local:4630946219955703956";
    private static boolean installed;
    private static boolean ready;
    private static volatile boolean prepared;
    private static volatile boolean presentationLayout;
    private static volatile int groupId = -1;
    private static Object service;
    private static Context context;
    private static Handler handler;
    private static IBinder owner;
    private static IBinder.DeathRecipient death;
    private static PowerManager.WakeLock dozeLock;
    private static PowerManager.WakeLock brightLock;
    private static PowerManager.WakeLock drawLock;
    private static volatile boolean active;
    private static volatile boolean sleeping;
    private static int dozeState = 4;
    private static volatile int displayId = -1;
    private static Object touch;
    private static final int[] TOUCH_MODES = {30, 14};
    private static final int[] previousTouchModes = {-1, -1};
    private static final Runnable suspend = () -> {
        try {
            synchronized (field(service, "mLock")) {
                if (!active || !sleeping || targetGroup() == null) return;
                dozeState = 4;
                updatePower();
                Log.i(TAG, "outer group=" + groupId + " DOZE_SUSPEND");
            }
        } catch (Throwable error) { Log.e(TAG, "Unable to suspend outer display", error); }
    };

    interface Callback { void run(Object instance, Object[] args) throws Throwable; }
    interface Hooks { void add(Method method, boolean after, Callback callback) throws Throwable; }

    static synchronized void install(ClassLoader loader, Hooks hooks) throws Throwable {
        if (installed || !"bixi".equals(Build.DEVICE) || Build.VERSION.SDK_INT != 37) return;
        Class<?> mapper = loader.loadClass("com.android.server.display.LogicalDisplayMapper");
        Class<?> logical = loader.loadClass("com.android.server.display.LogicalDisplay");
        Class<?> power = loader.loadClass("com.android.server.power.PowerManagerService");
        Class<?> group = loader.loadClass("com.android.server.power.PowerGroup");
        Method assign = method(mapper, "assignDisplayGroupLocked", logical);
        Method systemReady = method(power, "systemReady");
        Method update = null;
        for (Method candidate : group.getDeclaredMethods()) {
            if (candidate.getName().equals("updateLocked") && candidate.getParameterCount() == 17) update = candidate;
        }
        if (update == null) throw new NoSuchMethodException("PowerGroup.updateLocked");
        installed = true;
        hooks.add(assign, false, (instance, args) -> {
            if (!ready) return;
            Object state = field(instance, "mDeviceState");
            presentationLayout = (int) call(state, "getIdentifier") == 5;
            if (!presentationLayout && groupId > 0) {
                // Stop applying outer policy before layout swaps can make the same logical ID refer to the inner panel.
                groupId = -1;
                IBinder previousOwner = owner;
                handler.post(() -> {
                    try {
                        synchronized (field(service, "mLock")) {
                            if (!presentationLayout && owner == previousOwner) stopDisplay();
                        }
                    } catch (Throwable error) { Log.e(TAG, "Fold cleanup failed", error); }
                });
            }
            Object screen = args[0];
            Object device = call(screen, "getPrimaryDisplayDeviceLocked");
            if (device == null || !OUTER.equals(call(device, "getUniqueId"))) return;
            int id = (int) call(screen, "getDisplayIdLocked");
            boolean separate = prepared && id != 0 && presentationLayout;
            // Following the sleeping main panel would clamp the independently awake outer panel to minimum brightness.
            if (separate) call(screen, "setLeadDisplayLocked", -1);
            String name = (String) call(screen, "getLayoutGroupNameLocked");
            if (separate || GROUP.equals(name)) call(screen, "setDisplayGroupNameLocked", separate ? GROUP : "");
        });
        hooks.add(assign, true, (instance, args) -> {
            if (!ready) return;
            Object screen = args[0];
            if (GROUP.equals(call(screen, "getLayoutGroupNameLocked"))) {
                int assigned = (int) call(screen, "getDisplayGroupIdLocked");
                if (assigned != groupId) Log.i(TAG, "independent outer group=" + assigned);
                groupId = assigned;
            }
        });
        hooks.add(update, false, (instance, args) -> {
            if (!ready || !prepared || !presentationLayout || !active || !sleeping || groupId <= 0
                    || (int) call(instance, "getGroupId") != groupId) return;
            // Override only this group's Dream-equivalent request; the main display keeps its own AOD policy.
            args[4] = dozeState;
            args[5] = 1;
            args[6] = 0.02f;
            args[7] = false;
            args[8] = false;
            args[11] = false;
            args[15] = false;
        });
        hooks.add(systemReady, true, (instance, args) -> {
            if (!ready || service != null) return;
            service = instance;
            context = (Context) field(instance, "mContext");
            handler = (Handler) field(instance, "mHandler");
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    try {
                        synchronized (field(service, "mLock")) {
                            String command = intent.getStringExtra("command");
                            IBinder token = intent.getExtras() == null ? null : intent.getExtras().getBinder("owner");
                            if ("prepare".equals(command)) {
                                if (token == null || !token.isBinderAlive()) return;
                                release();
                                owner = token;
                                death = () -> handler.post(() -> {
                                    try { synchronized (field(service, "mLock")) { if (owner == token) release(); } }
                                    catch (Throwable error) { Log.e(TAG, "Owner cleanup failed", error); }
                                });
                                owner.linkToDeath(death, 0);
                                prepared = true;
                                setResultCode(PROTOCOL);
                            } else if ("release".equals(command) && owner == null) {
                                setResultCode(PROTOCOL);
                            } else if (owner != null && owner.equals(token)) {
                                if ("release".equals(command)) {
                                    release();
                                    setResultCode(PROTOCOL);
                                } else if ("state".equals(command)) {
                                    displayId = intent.getIntExtra("display", -1);
                                    Object target = targetGroup();
                                    if (target == null) {
                                        setResultCode(PENDING);
                                        return;
                                    }
                                    boolean dim = intent.getBooleanExtra("sleeping", true);
                                    boolean first = !active;
                                    boolean transition = first || dim != sleeping;
                                    active = true;
                                    sleeping = dim;
                                    handler.post(OuterDisplayPowerHooks::updateTouchGesture);
                                    ensureLocks();
                                    handler.removeCallbacks(suspend);
                                    if (dim) {
                                        dozeState = first || transition ? 2 : 3;
                                        if (!dozeLock.isHeld()) dozeLock.acquire();
                                        if (brightLock.isHeld()) brightLock.release();
                                        // This group has no system DreamService; notify PMS normally without starting the main-screen dream.
                                        call(target, "setWakefulnessLocked", 3, SystemClock.uptimeMillis(), 1000, 0, 0, null, null);
                                        call(target, "setSandmanSummonedLocked", false);
                                        drawLock.acquire(300L);
                                        handler.postDelayed(suspend, transition ? 6000L : 300L);
                                    } else {
                                        if (!brightLock.isHeld()) brightLock.acquire();
                                        if (dozeLock.isHeld()) dozeLock.release();
                                        call(service, "wakePowerGroupLocked", target, SystemClock.uptimeMillis(), 2,
                                                "MixFlipTool:outer", 1000, "android", 1000);
                                    }
                                    updatePower();
                                    Log.i(TAG, "outer display=" + displayId + " group=" + groupId + (dim ? " DOZING" : " AWAKE"));
                                    setResultCode(PROTOCOL);
                                }
                            }
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "Outer power command failed", error);
                        try { synchronized (field(service, "mLock")) { release(); } }
                        catch (Throwable cleanup) { Log.e(TAG, "Outer cleanup failed", cleanup); }
                    }
                }
            }, new IntentFilter(ACTION), android.Manifest.permission.DUMP, handler, Context.RECEIVER_EXPORTED);
            Log.i(TAG, "system power backend ready; protocol=" + PROTOCOL);
        });
        ready = true;
        Log.i(TAG, "system power hooks installed");
    }

    private static Object targetGroup() throws Throwable {
        if (!prepared || !presentationLayout || displayId <= 0 || groupId <= 0) return null;
        Object info = call(field(service, "mDisplayManagerInternal"), "getDisplayInfo", displayId);
        if (info == null || !OUTER.equals(field(info, "uniqueId")) || (int) field(info, "displayGroupId") != groupId) return null;
        Object target = ((SparseArray<?>) field(service, "mPowerGroups")).get(groupId);
        return target;
    }

    private static void ensureLocks() throws Throwable {
        if (dozeLock != null) return;
        PowerManager power = context.getSystemService(PowerManager.class);
        Method create = PowerManager.class.getMethod("newWakeLock", int.class, String.class, int.class);
        dozeLock = (PowerManager.WakeLock) create.invoke(power, 64, "MixFlipTool:outer-doze", displayId);
        brightLock = (PowerManager.WakeLock) create.invoke(power, 10, "MixFlipTool:outer-bright", displayId);
        drawLock = (PowerManager.WakeLock) create.invoke(power, 128, "MixFlipTool:outer-draw", displayId);
        dozeLock.setReferenceCounted(false);
        brightLock.setReferenceCounted(false);
        drawLock.setReferenceCounted(false);
    }

    private static void release() throws Throwable {
        prepared = false;
        if (owner != null && death != null) owner.unlinkToDeath(death, 0);
        owner = null;
        death = null;
        stopDisplay();
    }

    private static void stopDisplay() throws Throwable {
        if (handler != null) handler.removeCallbacks(suspend);
        active = false;
        if (handler != null) handler.post(OuterDisplayPowerHooks::updateTouchGesture);
        for (PowerManager.WakeLock lock : new PowerManager.WakeLock[]{drawLock, brightLock, dozeLock}) {
            if (lock != null && lock.isHeld()) lock.release();
        }
        drawLock = brightLock = dozeLock = null;
        displayId = -1;
        groupId = -1;
        if (service != null) updatePower();
    }

    static Method inputPolicyMethod(ClassLoader loader) throws Throwable {
        if (!installed) return null;
        return method(loader.loadClass("com.android.server.policy.PhoneWindowManager"),
                "shouldDispatchInputWhenNonInteractive", int.class, int.class);
    }

    static boolean allowOuterInput(int queriedDisplayId) {
        return ready && prepared && presentationLayout && active && sleeping && groupId > 0
                && displayId > 0 && queriedDisplayId == displayId;
    }

    static Method wakeKeyMethod(ClassLoader loader) throws Throwable {
        if (!installed) return null;
        return method(loader.loadClass("com.android.server.input.InputManagerService"),
                "interceptKeyBeforeQueueing", KeyEvent.class, int.class);
    }

    static boolean handleOuterWakeKey(KeyEvent event, int policyFlags) {
        if (!ready || !prepared || !presentationLayout || !active || groupId <= 0 || displayId <= 0
                || (policyFlags & 0x03000000) != 0x02000000
                || event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP || event.getScanCode() != 143) return false;
        InputDevice device = event.getDevice();
        if (device == null || !"goodix_ts".equals(device.getName())) return false;
        // Suspended Goodix reports KEY_WAKEUP instead of touch coordinates; keep it away from the main-screen wake policy.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && sleeping) {
            Log.i(TAG, "outer hardware double tap device=" + event.getDeviceId());
            handler.post(() -> {
                if (prepared && presentationLayout && active && sleeping) {
                    context.sendBroadcast(new Intent(GESTURE_ACTION).setPackage("com.android.systemui"),
                            android.Manifest.permission.DUMP);
                }
            });
        }
        return true;
    }

    private static void updateTouchGesture() {
        try {
            boolean enable = prepared && presentationLayout && active && groupId > 0 && displayId > 0;
            if (!enable && previousTouchModes[0] < 0 && previousTouchModes[1] < 0) return;
            if (touch == null) {
                touch = Class.forName("miui.util.ITouchFeature").getMethod("getInstance").invoke(null);
                if (touch == null) return;
            }
            for (int i = 0; i < TOUCH_MODES.length; i++) {
                int mode = TOUCH_MODES[i];
                if (enable) {
                    if (previousTouchModes[i] >= 0) continue;
                    int previous = (int) call(touch, "getTouchModeCurValue", 1, mode);
                    if (previous < 0) throw new IllegalStateException("Unable to read outer touch mode " + mode);
                    // Goodix checks wakeup-enable (30) as well as double-tap (14) before reporting KEY_WAKEUP.
                    if (!(boolean) call(touch, "setTouchMode", 1, mode, 1)) {
                        throw new IllegalStateException("Unable to enable outer touch mode " + mode);
                    }
                    previousTouchModes[i] = previous;
                    Log.i(TAG, "outer touch mode=" + mode + " enabled; previous=" + previous);
                } else {
                    if (previousTouchModes[i] < 0) continue;
                    if (!(boolean) call(touch, "setTouchMode", 1, mode, previousTouchModes[i])) {
                        throw new IllegalStateException("Unable to restore outer touch mode " + mode);
                    }
                    Log.i(TAG, "outer touch mode=" + mode + " restored=" + previousTouchModes[i]);
                    previousTouchModes[i] = -1;
                }
            }
        } catch (Throwable error) { Log.e(TAG, "Outer touch gesture update failed", error); }
    }

    private static void updatePower() throws Throwable {
        Field dirty = findField(service.getClass(), "mDirty");
        dirty.setInt(service, dirty.getInt(service) | 32);
        call(service, "updatePowerStateLocked");
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field result = current.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object field(Object instance, String name) throws Throwable { return findField(instance.getClass(), name).get(instance); }

    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method result = type.getDeclaredMethod(name, args);
        result.setAccessible(true);
        return result;
    }

    private static Object call(Object instance, String name, Object... args) throws Throwable {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                boolean matches = true;
                for (int i = 0; i < args.length; i++) {
                    Class<?> parameter = parameters[i];
                    if (parameter == int.class) parameter = Integer.class;
                    else if (parameter == long.class) parameter = Long.class;
                    else if (parameter == boolean.class) parameter = Boolean.class;
                    if (args[i] != null && !parameter.isInstance(args[i])) { matches = false; break; }
                }
                if (!matches) continue;
                candidate.setAccessible(true);
                return candidate.invoke(instance, args);
            }
        }
        throw new NoSuchMethodException(instance.getClass().getName() + "." + name);
    }
}
