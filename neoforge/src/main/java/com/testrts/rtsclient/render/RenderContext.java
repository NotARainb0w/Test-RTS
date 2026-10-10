package com.testrts.rtsclient.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

/**
 * What world rendering hands to the overlay: {@code matrixStack} holds the view rotation with the camera at the
 * origin (world positions are drawn relative to the camera), and RenderSystem's model-view matrix is identity.
 */
public record RenderContext(PoseStack matrixStack, Matrix4f projectionMatrix, Camera camera, float tickDelta,
                            MultiBufferSource.BufferSource consumers) {
}
