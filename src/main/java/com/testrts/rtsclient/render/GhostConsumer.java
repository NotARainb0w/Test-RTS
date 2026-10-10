package com.testrts.rtsclient.render;

import net.minecraft.client.render.VertexConsumer;

/** Passes vertices through, making them see-through and fully lit (ghosts shouldn't go dark underground). */
final class GhostConsumer implements VertexConsumer {
    private static final int FULL_BRIGHT = 240;

    private final VertexConsumer delegate;
    private final float alpha;

    GhostConsumer(VertexConsumer delegate, float alpha) {
        this.delegate = delegate;
        this.alpha = alpha;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        this.delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        this.delegate.color(red, green, blue, (int) (alpha * this.alpha));
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        this.delegate.texture(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        this.delegate.overlay(u, v);
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        this.delegate.light(FULL_BRIGHT, FULL_BRIGHT);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        this.delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void next() {
        this.delegate.next();
    }

    @Override
    public void fixedColor(int red, int green, int blue, int alpha) {
        this.delegate.fixedColor(red, green, blue, (int) (alpha * this.alpha));
    }

    @Override
    public void unfixColor() {
        this.delegate.unfixColor();
    }
}
