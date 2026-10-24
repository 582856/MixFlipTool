package com.netease.cloudmusic.utils;

/** Supplies display density without starting the player's Application singleton. */
public final class xt {
    public static float density;
    public static float i(float value) { return value * density; }
}
