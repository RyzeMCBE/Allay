package org.allaymc.server.entity.effect;

import org.allaymc.api.entity.component.EntityUndeadComponent;
import org.allaymc.api.entity.damage.DamageContainer;
import org.allaymc.api.entity.effect.EffectInstance;
import org.allaymc.api.entity.interfaces.EntityLiving;
import org.allaymc.api.utils.identifier.Identifier;

import java.awt.*;

/**
 * @author daoge_cmd
 */
public class EffectInstantHealthType extends AbstractEffectType {
    public EffectInstantHealthType() {
        super(6, new Identifier("minecraft:instant_health"), new Color(0xf82423));
    }

    @Override
    public boolean isInstant() {
        return true;
    }

    @Override
    public void onAdd(EntityLiving entity, EffectInstance effectInstance) {
        var level = effectInstance.getLevel();
        var intensity = effectInstance.getIntensity();
        var undead = entity instanceof EntityUndeadComponent;
        var amount = (float) Math.floor((undead ? 3 : 2) * Math.pow(2, level) * intensity + 0.5);
        if (amount <= 0) return;

        if (undead) {
            entity.attack(DamageContainer.magicEffect(amount));
            return;
        }

        entity.setHealth(entity.getHealth() + amount);
    }
}
