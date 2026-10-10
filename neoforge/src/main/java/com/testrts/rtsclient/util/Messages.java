package com.testrts.rtsclient.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Short-lived status lines shown in the RTS screen (and mirrored to the action bar outside it). */
public final class Messages {
    private static final int MAX = 6;
    private static final long LIFETIME_MS = 6000L;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    /** {@code kind} picks the accent colour of the notification strip. */
    public enum Kind { INFO, SUCCESS, WARN, ERROR, CHAT }

    public record Entry(Component text, long createdAt, Kind kind) {
    }

    private Messages() {
    }

    public static void info(String message) {
        push(Component.literal(message).withStyle(ChatFormatting.WHITE), Kind.INFO);
    }

    public static void success(String message) {
        push(Component.literal(message).withStyle(ChatFormatting.GREEN), Kind.SUCCESS);
    }

    public static void warn(String message) {
        push(Component.literal(message).withStyle(ChatFormatting.GOLD), Kind.WARN);
    }

    public static void error(String message) {
        push(Component.literal(message).withStyle(ChatFormatting.RED), Kind.ERROR);
    }

    /** A chat line, shown in the RTS screen only (it's already in chat). */
    public static void chat(Component text) {
        synchronized (ENTRIES) {
            ENTRIES.addLast(new Entry(text, System.currentTimeMillis(), Kind.CHAT));
            while (ENTRIES.size() > MAX) {
                ENTRIES.removeFirst();
            }
        }
    }

    private static void push(Component text, Kind kind) {
        synchronized (ENTRIES) {
            ENTRIES.addLast(new Entry(text, System.currentTimeMillis(), kind));
            while (ENTRIES.size() > MAX) {
                ENTRIES.removeFirst();
            }
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && !com.testrts.rtsclient.RtsController.get().isActive()) {
            client.player.displayClientMessage(Component.literal("[RTS] ").withStyle(ChatFormatting.AQUA).append(text), true);
        }
    }

    /** Lines still visible, oldest first, with their age-based alpha (0..1). */
    public static List<Entry> visible() {
        long now = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>();
        synchronized (ENTRIES) {
            ENTRIES.removeIf(e -> now - e.createdAt() > LIFETIME_MS);
            out.addAll(ENTRIES);
        }
        return out;
    }

    public static float alpha(Entry entry) {
        long age = System.currentTimeMillis() - entry.createdAt();
        long fade = LIFETIME_MS - 1000L;
        if (age < fade) {
            return 1.0F;
        }
        return Math.max(0.0F, 1.0F - (age - fade) / 1000.0F);
    }
}
