package org.allaymc.server.container.processor;

import org.allaymc.api.container.Container;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.eventbus.event.container.ContainerItemMoveEvent;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.type.ItemTypes;
import org.allaymc.api.pdc.PersistentDataType;
import org.allaymc.api.player.Player;
import org.allaymc.api.server.Server;
import org.allaymc.api.utils.identifier.Identifier;
import org.allaymc.server.container.impl.BaseContainer;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TransferItemStackRequestAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class TransferItemActionProcessorTest {
    private static final Identifier ABILITY = new Identifier("ryze:ability_key");
    private static final Identifier USES = new Identifier("ryze:ability_uses");

    @Test
    void wholePartnerStackRetainsItsDataAfterChestReload() {
        var source = new BaseContainer(ContainerTypes.INVENTORY);
        var chest = new BaseContainer(ContainerTypes.CHEST);
        var item = ability("switcher", 5, 8);
        var data = item.saveExtraTag();
        source.setItemStack(0, item, false);

        assertTrue(move(source, chest, 8, mock(EntityPlayer.class)));
        assertTrue(source.isEmpty(0));
        var reopened = new BaseContainer(ContainerTypes.CHEST);
        reopened.loadNBT(chest.saveNBT());
        assertEquals(8, reopened.getItemStack(0).getCount());
        assertEquals(data, reopened.getItemStack(0).saveExtraTag());
    }

    @Test
    void splittingPartnerStackPreservesBothIdentitiesAndCounts() {
        var source = new BaseContainer(ContainerTypes.INVENTORY);
        var chest = new BaseContainer(ContainerTypes.CHEST);
        var item = ability("switcher", 5, 8);
        var data = item.saveExtraTag();
        source.setItemStack(0, item, false);

        assertTrue(move(source, chest, 3, mock(EntityPlayer.class)));
        assertEquals(5, source.getItemStack(0).getCount());
        assertEquals(3, chest.getItemStack(0).getCount());
        assertEquals(data, source.getItemStack(0).saveExtraTag());
        assertEquals(data, chest.getItemStack(0).saveExtraTag());
        assertNotEquals(source.getItemStack(0).getUniqueId(), chest.getItemStack(0).getUniqueId());
    }

    @Test
    void differentAbilityKeysCannotMergeEvenWithIdenticalVisibleNames() {
        rejectMerge(ability("switcher", 5, 8), ability("ninja_star", 5, 4));
    }

    @Test
    void differentRemainingUsesCannotMerge() {
        rejectMerge(ability("switcher", 5, 8), ability("switcher", 2, 4));
    }

    @Test
    void partialMergeChecksRequestedCountInsteadOfEntireSourceStack() {
        var source = new BaseContainer(ContainerTypes.INVENTORY);
        var chest = new BaseContainer(ContainerTypes.CHEST);
        source.setItemStack(0, ability("switcher", 5, 40), false);
        chest.setItemStack(0, ability("switcher", 5, 40), false);

        assertTrue(move(source, chest, 8, mock(EntityPlayer.class)));
        assertEquals(32, source.getItemStack(0).getCount());
        assertEquals(48, chest.getItemStack(0).getCount());
        assertEquals("switcher", chest.getItemStack(0).getPersistentDataContainer().get(ABILITY, PersistentDataType.STRING));
    }

    @Test
    void playerActorIsAvailableToProtectionBeforeEitherSlotChanges() {
        var source = new BaseContainer(ContainerTypes.INVENTORY);
        var chest = new BaseContainer(ContainerTypes.CHEST);
        source.setItemStack(0, ability("switcher", 5, 8), false);
        var actor = mock(EntityPlayer.class);
        var veto = new ActorVeto(actor);
        Server.getInstance().getEventBus().registerListener(veto);
        try {
            assertFalse(move(source, chest, 3, actor));
            assertEquals(1, veto.calls);
            assertEquals(8, source.getItemStack(0).getCount());
            assertTrue(chest.isEmpty(0));
        } finally {
            Server.getInstance().getEventBus().unregisterListener(veto);
        }
    }

    @Test
    void automationConstructorRetainsItsNullActor() {
        var source = new BaseContainer(ContainerTypes.HOPPER);
        var chest = new BaseContainer(ContainerTypes.CHEST);
        assertNull(new ContainerItemMoveEvent(source, 0, chest, 0, ItemTypes.STICK, 1).getPlayer());
    }

    private void rejectMerge(ItemStack sourceItem, ItemStack destinationItem) {
        var source = new BaseContainer(ContainerTypes.INVENTORY);
        var chest = new BaseContainer(ContainerTypes.CHEST);
        var sourceData = sourceItem.saveNBT();
        var destinationData = destinationItem.saveNBT();
        source.setItemStack(0, sourceItem, false);
        chest.setItemStack(0, destinationItem, false);

        assertFalse(move(source, chest, 3, mock(EntityPlayer.class)));
        assertEquals(sourceData, source.getItemStack(0).saveNBT());
        assertEquals(destinationData, chest.getItemStack(0).saveNBT());
    }

    private static ItemStack ability(String key, int uses, int count) {
        var item = ItemTypes.STICK.createItemStack(count);
        item.setCustomName("Partner Item");
        item.getPersistentDataContainer().set(ABILITY, PersistentDataType.STRING, key);
        item.getPersistentDataContainer().set(USES, PersistentDataType.INTEGER, uses);
        return item;
    }

    private boolean move(Container source, Container destination, int count, EntityPlayer actor) {
        var player = mock(Player.class);
        when(player.getControlledEntity()).thenReturn(actor);
        var action = mock(TransferItemStackRequestAction.class, RETURNS_DEEP_STUBS);
        when(action.count()).thenReturn(count);
        var from = action.source().containerName();
        var to = action.destination().containerName();
        try (var access = mockStatic(ContainerActionProcessor.class)) {
            access.when(() -> ContainerActionProcessor.getContainerFrom(player, from)).thenReturn(source);
            access.when(() -> ContainerActionProcessor.getContainerFrom(player, to)).thenReturn(destination);
            access.when(() -> ContainerActionProcessor.canPlaceItemToSlot(destination, 0, source.getItemStack(0))).thenReturn(true);
            access.when(() -> ContainerActionProcessor.getSlotType(source, 0)).thenReturn(ContainerSlotType.HOTBAR);
            access.when(() -> ContainerActionProcessor.getSlotType(destination, 0)).thenReturn(ContainerSlotType.LEVEL_ENTITY);
            var processor = new TransferItemActionProcessor<TransferItemStackRequestAction>() {
                @Override
                public ItemStackRequestActionType getType() {
                    return ItemStackRequestActionType.PLACE;
                }
            };
            return processor.handle(action, player, 0, new ItemStackRequestAction[]{action}, new HashMap<>()).ok();
        }
    }

    public static final class ActorVeto {
        private final EntityPlayer expected;
        private int calls;

        ActorVeto(EntityPlayer expected) {
            this.expected = expected;
        }

        @EventHandler
        public void move(ContainerItemMoveEvent event) {
            assertSame(expected, event.getPlayer());
            calls++;
            event.cancel();
        }
    }
}
