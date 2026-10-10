package com.testrts.rtsclient.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Passes vertices through with the preview's opacity, keeping the world's lighting but never darker
 * than {@link #MIN_LIGHT} so previews inside terrain or caves stay visible.
 */
final class GhostConsumer implements VertexConsumer {
    /** Light level 10 of 15, in lightmap units. */
    private static final int MIN_LIGHT = 10 * 16;

    private final VertexConsumer delegate;
    private final float alpha;

    GhostConsumer(VertexConsumer delegate, float alpha) {
        this.delegate = delegate;
        this.alpha = alpha;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        this.delegate.setColor(red, green, blue, (int) (alpha * this.alpha));
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        this.delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.delegate.setUv2(Math.max(u, MIN_LIGHT), Math.max(v, MIN_LIGHT));
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.delegate.setNormal(x, y, z);
        return this;
    }
}
