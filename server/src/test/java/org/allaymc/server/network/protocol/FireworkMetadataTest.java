package org.allaymc.server.network.protocol;

import org.allaymc.api.entity.EntityInitInfo;
import org.allaymc.api.entity.action.SimpleEntityAction;
import org.allaymc.api.entity.type.EntityTypes;
import org.allaymc.api.utils.DyeColor;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.data.FireworkType;
import org.allaymc.api.world.explosion.FireworkExplosion;
import org.allaymc.server.network.protocol.v2193.PacketEncoder_v2193;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType;
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(AllayTestExtension.class)
class FireworkMetadataTest {
    @Test
    void currentBedrockReceivesOrangeStarAndExplosionForTheSameActor() {
        var rocket = EntityTypes.FIREWORKS_ROCKET.createEntity(EntityInitInfo.builder()
                .dimension(mock(Dimension.class)).pos(1,80,2).build());
        rocket.setExistenceTicks(25);
        var explosion = new FireworkExplosion(FireworkType.STAR,List.of(DyeColor.ORANGE),List.of(),false,true);
        rocket.setExplosions(Set.of(explosion));
        var encoder = new PacketEncoder_v2193(mock(ProtocolData.class));
        var fireworks = encoder.encodeEntityMetadata(rocket).get(EntityDataTypes.DISPLAY_FIREWORK).getCompound("Fireworks");
        var effect = fireworks.getList("Explosions",NbtType.COMPOUND).getFirst();
        assertEquals((byte) 2,effect.getByte("FireworkType"));
        assertArrayEquals(new byte[]{14},effect.getByteArray("FireworkColor"));
        assertTrue(effect.getBoolean("FireworkTrail"));
        var action = (EntityEventPacket) encoder.encodeEntityAction(rocket,SimpleEntityAction.FIREWORK_EXPLODE,false).iterator().next();
        assertEquals(rocket.getRuntimeId(),action.getRuntimeEntityId());
        assertEquals(EntityEventType.FIREWORK_EXPLODE,action.getType());
    }
}
