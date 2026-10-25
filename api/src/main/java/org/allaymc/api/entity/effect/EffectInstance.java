package org.allaymc.api.entity.effect;

import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.allaymc.api.registry.Registries;
import org.cloudburstmc.nbt.NbtMap;
import org.jetbrains.annotations.Range;

/**
 * EffectInstance represents an active effect, which contains the level of the effect, its
 * duration, and whether the particle effect emitted can be seen by other players.
 *
 * @author daoge_cmd
 */
@Setter
@Getter
@ToString
public final class EffectInstance {
    /**
     * The type of this effect instance.
     */
    private final EffectType type;
    /**
     * The amplifier of this effect instance. It is equals to {@code getLevel() - 1}.
     */
    @Range(from = 0, to = Short.MAX_VALUE)
    private int amplifier;
    /**
     * The duration of this effect instance, and the unit is game tick.
     */
    private int duration;
    /**
     * Whether the effect is ambient, leading to reduced particles shown to the client.
     */
    private boolean ambient;
    /**
     * Whether the particle is visible to players.
     */
    private boolean visible;
    /**
     * Intensity of an instant effect, from zero to one. Not persisted in entity NBT.
     */
    private double intensity = 1.0;

    /**
     * Creates an effect with full intensity and a duration measured in game ticks.
     */
    public EffectInstance(EffectType type, int amplifier, int duration, boolean ambient, boolean visible) {
        this.type = type;
        this.amplifier = amplifier;
        this.duration = duration;
        this.ambient = ambient;
        this.visible = visible;
    }

    /**
     * Sets the intensity used by instant effects.
     *
     * @throws IllegalArgumentException if intensity is not finite or outside zero to one
     */
    public void setIntensity(double intensity) {
        if (!Double.isFinite(intensity) || intensity < 0 || intensity > 1) {
            throw new IllegalArgumentException("Effect intensity must be finite and between zero and one");
        }
        this.intensity = intensity;
    }

    /**
     * A constructor that creates a copy of the given effect instance.
     *
     * @param effect the effect instance that based on
     */
    public EffectInstance(EffectInstance effect) {
        this.type = effect.type;
        this.amplifier = effect.amplifier;
        this.duration = effect.duration;
        this.ambient = effect.ambient;
        this.visible = effect.visible;
        this.intensity = effect.intensity;
    }

    /**
     * Creates a new effect instance from the given nbt.
     *
     * @param nbt the nbt that contains the effect information
     * @return a new effect instance based on the given nbt
     */
    public static EffectInstance fromNBT(NbtMap nbt) {
        var effectType = Registries.EFFECTS.getByK1((int) nbt.getByte("Id"));
        Preconditions.checkNotNull(effectType, "Effect type not found for id: " + nbt.getByte("Id") + "!");
        var amplifier = nbt.getByte("Amplifier");
        var duration = nbt.getInt("Duration");
        var ambient = nbt.getBoolean("Ambient");
        var visible = nbt.getBoolean("ShowParticles");
        return new EffectInstance(effectType, amplifier, duration, ambient, visible);
    }

    /**
     * Gets the level of this effect instance.
     *
     * @return the level of this effect instance
     */
    @Range(from = 1, to = Short.MAX_VALUE + 1)
    public int getLevel() {
        return getAmplifier() + 1;
    }

    /**
     * Saves this effect instance to nbt. The nbt can be used in {@link #fromNBT(NbtMap)} later.
     *
     * @return the nbt that contains the information of this effect instance
     */
    public NbtMap saveNBT() {
        return NbtMap.builder()
                .putByte("Id", (byte) type.getId())
                .putByte("Amplifier", (byte) amplifier)
                // TODO: DurationEasy, DurationNormal, DurationHard
                .putInt("Duration", duration)
                .putBoolean("Ambient", ambient)
                // TODO: DisplayOnScreenTextureAnimation
                .putBoolean("ShowParticles", visible)
                .build();
    }
}
