package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.exec.Inventories;
import com.testrts.rtsclient.exec.Look;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Walk up to a mob, following it as it moves, and use the selected item on it: shear a sheep,
 * milk a cow, leash it, name it, feed it... or, with a weapon (or an empty hand against a hostile
 * mob), attack it until it's dead.
 */
public final class EntityTask implements RtsTask {
    /** A little inside the 3-block entity reach, so the server never refuses the hit. */
    private static final double REACH = 2.8;
    private static final int TIMEOUT_TICKS = 20 * 60;
    /** Walk again when the mob has moved this far (squared) from where we were heading. */
    private static final double MOVED_SQ = 4.0;

    private final Entity target;
    /** The item to use, or null for an empty hand. */
    @Nullable
    private final Item item;
    private final boolean attack;
    private final String label;
    @Nullable
    private BlockPos heading;
    private int repathIn;
    private int ticks;
    private int hits;

    public EntityTask(Entity target, @Nullable Item item) {
        this.target = target;
        this.item = item;
        this.attack = attacks(item, target);
        this.label = describe(item, target);
    }

    /** Hit it (weapon, or an empty hand against a hostile mob) rather than use the item on it. */
    public static boolean attacks(@Nullable Item item, Entity target) {
        if (item instanceof SwordItem || item instanceof AxeItem || item instanceof TridentItem) {
            return true;
        }
        return item == null && target instanceof Enemy;
    }

    /** "Shear Sheep", "Attack Zombie", "Use Wheat on Cow"... */
    public static String describe(@Nullable Item item, Entity target) {
        String name = target.getDisplayName().getString();
        if (attacks(item, target)) {
            return "Attack " + name;
        }
        if (item == null) {
            return "Use " + name;
        }
        if (item == Items.SHEARS) {
            return "Shear " + name;
        }
        if (item == Items.BUCKET) {
            return "Milk " + name;
        }
        if (item == Items.LEAD) {
            return "Leash " + name;
        }
        if (item == Items.NAME_TAG) {
            return "Name " + name;
        }
        if (item == Items.SADDLE) {
            return "Saddle " + name;
        }
        return "Use " + new ItemStack(item).getHoverName().getString() + " on " + name;
    }

    @Override
    public String label() {
        return this.label;
    }

    @Override
    public String detail() {
        if (this.attack && this.hits > 0) {
            return this.hits + (this.hits == 1 ? " hit" : " hits");
        }
        return this.heading != null ? "walking over" : "at " + this.target.blockPosition().toShortString();
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return Status.FAILED;
        }
        if (this.target.isRemoved() || !this.target.isAlive()) {
            stopWalking();
            if (this.attack) {
                Messages.success(this.label + ": done");
            }
            return Status.DONE;
        }
        if (++this.ticks > TIMEOUT_TICKS) {
            stopWalking();
            Messages.warn(this.label + ": couldn't get to it");
            return Status.FAILED;
        }
        if (player.distanceToSqr(this.target) > REACH * REACH) {
            // Follow it: walk again when it has moved off, or every couple of seconds.
            BlockPos at = this.target.blockPosition();
            if (this.heading == null || this.heading.distSqr(at) > MOVED_SQ || --this.repathIn <= 0
                    || !Engine.isMoving()) {
                if (!Engine.goNextTo(at)) {
                    Messages.warn(this.label + ": no way to get there");
                    return Status.FAILED;
                }
                this.heading = at.immutable();
                this.repathIn = 40;
            }
            return Status.RUNNING;
        }
        stopWalking();
        if (!holdItem(client)) {
            Messages.warn(this.label + ": no " + new ItemStack(this.item).getHoverName().getString() + " to use");
            return Status.FAILED;
        }
        // Turn to it first (no snapping), then act.
        if (!Look.at(player, this.target.getBoundingBox().getCenter())) {
            return Status.RUNNING;
        }
        if (this.attack) {
            if (player.getAttackStrengthScale(0.5F) >= 1.0F) {
                client.gameMode.attack(player, this.target);
                player.swing(InteractionHand.MAIN_HAND);
                this.hits++;
            }
            return Status.RUNNING;
        }
        client.gameMode.interact(player, this.target, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        return Status.DONE;
    }

    /** The chosen item in hand; with an empty hand and a fight ahead, the best weapon we carry. */
    private boolean holdItem(Minecraft client) {
        if (this.item != null) {
            return Inventories.hold(client, this.item);
        }
        if (this.attack) {
            for (Item weapon : new Item[]{Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.NETHERITE_AXE,
                    Items.DIAMOND_AXE, Items.STONE_SWORD, Items.IRON_AXE, Items.GOLDEN_SWORD, Items.WOODEN_SWORD}) {
                if (Inventories.count(client, weapon) > 0) {
                    return Inventories.hold(client, weapon);
                }
            }
        }
        return true;
    }

    private void stopWalking() {
        if (this.heading != null) {
            Engine.stop();
            this.heading = null;
        }
    }

    @Override
    public void cancel(Minecraft client) {
        stopWalking();
    }
}
