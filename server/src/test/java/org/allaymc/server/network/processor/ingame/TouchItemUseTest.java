package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.container.interfaces.InventoryContainer;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.component.ItemEdibleComponent;
import org.allaymc.api.item.interfaces.ItemGoldenAppleStack;
import org.allaymc.api.item.type.ItemType;
import org.allaymc.api.math.location.Location3d;
import org.allaymc.api.player.InputMode;
import org.allaymc.api.player.Player;
import org.allaymc.api.world.Dimension;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.joml.Vector3ic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class TouchItemUseTest {
    @Test
    void touchingTheGroundDoesNotStartEatingOrLaunchAnItem() {
        for (boolean sustained : new boolean[]{false, true}) {
            var fixture = new Fixture(sustained, sustained);
            fixture.accept(use(0, ItemUseTransaction.TriggerType.PLAYER_INPUT));
            assertFalse(fixture.using.get());
            verify(fixture.item, never()).rightClickItemInAir(any());
            when(fixture.player.canPlaceBlocks()).thenReturn(false);
            fixture.accept(use(0, ItemUseTransaction.TriggerType.PLAYER_INPUT));
            assertFalse(fixture.using.get());
            verify(fixture.item, never()).rightClickItemInAir(any());
        }
    }

    @Test
    void explicitProjectilePressWorksOnceAndSimulationCannotRepeatIt() {
        var fixture = new Fixture(false, false);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.PLAYER_INPUT));
        for (int i = 0; i < 4; i++) fixture.accept(use(1, ItemUseTransaction.TriggerType.SIMULATION_TICK));
        verify(fixture.item, times(1)).rightClickItemInAir(fixture.entity);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.PLAYER_INPUT));
        verify(fixture.item, times(2)).rightClickItemInAir(fixture.entity);
    }

    @Test
    void automaticFoodCompletionFinishesOnlyTheStartedUseAfterItsDuration() {
        var fixture = new Fixture(true, true);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.SIMULATION_TICK));
        assertFalse(fixture.using.get());
        fixture.accept(use(1, ItemUseTransaction.TriggerType.PLAYER_INPUT));
        fixture.tick.set(10);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.SIMULATION_TICK));
        assertTrue(fixture.using.get());
        fixture.tick.set(31);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.SIMULATION_TICK));
        assertFalse(fixture.using.get());
        fixture.tick.set(62);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.SIMULATION_TICK));
        verify(fixture.item, times(1)).useItemInAir(fixture.entity, 31);
        assertFalse(fixture.using.get());
    }

    @Test
    void bowWaitsForReleaseInsteadOfFiringOnSimulationTicks() {
        var fixture = new Fixture(true, false);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.PLAYER_INPUT));
        fixture.tick.set(50);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.SIMULATION_TICK));
        assertTrue(fixture.using.get());
        verify(fixture.item, never()).useItemInAir(any(), anyLong());
        fixture.accept(release());
        verify(fixture.item).useItemInAir(fixture.entity, 50);
        assertFalse(fixture.using.get());
    }

    @Test
    void stoppingTouchUseAndSwitchingSlotsCannotTransferACharge() {
        var fixture = new Fixture(true, false);
        fixture.accept(use(1, ItemUseTransaction.TriggerType.PLAYER_INPUT));
        var stop = new PlayerActionPacket();
        stop.setAction(PlayerActionType.STOP_ITEM_USE_ON);
        new PlayerActionPacketProcessor().handleAsync(fixture.player, stop, 0);
        assertFalse(fixture.using.get());

        fixture.accept(use(1, ItemUseTransaction.TriggerType.PLAYER_INPUT));
        fixture.tick.set(50);
        var equip = new MobEquipmentPacket();
        equip.setContainerId(0);
        equip.setHotbarSlot(1);
        new MobEquipmentPacketProcessor().handleSync(fixture.player, equip, 50);
        assertFalse(fixture.using.get());
        fixture.accept(release());
        verify(fixture.replacement, never()).useItemInAir(any(), anyLong());
    }

    @Test
    void mouseBlockUseKeepsItsExistingFallback() {
        var fixture = new Fixture(false, false);
        when(fixture.player.getInputMode()).thenReturn(InputMode.MOUSE);
        fixture.accept(use(0, ItemUseTransaction.TriggerType.PLAYER_INPUT));
        verify(fixture.item).rightClickItemInAir(fixture.entity);
    }

    private static InventoryTransactionPacket use(int action, ItemUseTransaction.TriggerType trigger) {
        var packet = new InventoryTransactionPacket();
        packet.setTransactionType(InventoryTransactionType.ITEM_USE);
        packet.setActionType(action);
        packet.setTriggerType(trigger);
        packet.setBlockFace(1);
        packet.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.from(0, 63, 0));
        packet.setClickPosition(org.cloudburstmc.math.vector.Vector3f.from(0.5F, 1, 0.5F));
        return packet;
    }

    private static InventoryTransactionPacket release() {
        var packet = new InventoryTransactionPacket();
        packet.setTransactionType(InventoryTransactionType.ITEM_RELEASE);
        packet.setActionType(0);
        return packet;
    }

    private static final class Fixture {
        private final EntityPlayer entity = mock(EntityPlayer.class);
        private final Player player = mock(Player.class);
        private final ItemStack item;
        private final ItemStack replacement = mock(ItemStack.class);
        private final AtomicBoolean using = new AtomicBoolean();
        private final AtomicBoolean usingBlock = new AtomicBoolean();
        private final AtomicInteger slot = new AtomicInteger();
        private final AtomicLong tick = new AtomicLong();
        private final AtomicLong started = new AtomicLong();
        private final InventoryTransactionPacketProcessor processor = new InventoryTransactionPacketProcessor();

        private Fixture(boolean sustained, boolean edible) {
            item = edible ? mock(ItemGoldenAppleStack.class) : mock(ItemStack.class);
            if (edible) when(((ItemEdibleComponent) item).getEatingTime()).thenReturn(31);
            var dimension = mock(Dimension.class);
            var block = mock(BlockState.class, RETURNS_DEEP_STUBS);
            when(dimension.getBlockState(any(Vector3ic.class))).thenReturn(block);
            when(entity.getDimension()).thenReturn(dimension);
            when(entity.getLocation()).thenReturn(new Location3d(0, 64, 0, dimension));
            when(player.getControlledEntity()).thenReturn(entity);
            when(player.getInputMode()).thenReturn(InputMode.TOUCH);
            when(player.canInteractWithBlocks()).thenReturn(true);
            when(player.canPlaceBlocks()).thenReturn(true);
            when(entity.getController()).thenReturn(player);
            var inventory = mock(InventoryContainer.class);
            when(entity.getContainer(ContainerTypes.INVENTORY)).thenReturn(inventory);
            when(inventory.getHandSlot()).thenAnswer(call -> slot.get());
            when(inventory.getItemInHand()).thenAnswer(call -> slot.get() == 0 ? item : replacement);
            when(inventory.getItemStack(anyInt())).thenAnswer(call -> call.<Integer>getArgument(0) == 0 ? item : replacement);
            doAnswer(call -> { slot.set(call.getArgument(0)); return null; }).when(inventory).setHandSlot(anyInt());
            doCallRealMethod().when(entity).setHandSlot(anyInt(), anyBoolean());
            when(entity.getItemInHand()).thenAnswer(call -> inventory.getItemInHand());
            doReturn(mock(ItemType.class)).when(item).getItemType();
            doReturn(mock(ItemType.class)).when(replacement).getItemType();
            when(item.canUseItemInAir(entity)).thenReturn(sustained);
            when(entity.isUsingItemInAir()).thenAnswer(call -> using.get());
            when(entity.isUsingItemOnBlock()).thenAnswer(call -> usingBlock.get());
            doAnswer(call -> {
                boolean value = call.getArgument(0);
                using.set(value);
                if (value) started.set(tick.get());
                return null;
            }).when(entity).setUsingItemInAir(anyBoolean());
            doAnswer(call -> { usingBlock.set(call.getArgument(0)); return null; })
                    .when(entity).setUsingItemOnBlock(anyBoolean());
            when(entity.getItemUsingInAirTime()).thenAnswer(call -> tick.get() - started.get());
        }

        private void accept(InventoryTransactionPacket packet) {
            processor.handleSync(player, packet, tick.get());
        }
    }
}
