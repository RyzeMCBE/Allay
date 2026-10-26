package org.allaymc.server.network.protocol;

import org.allaymc.api.container.Container;
import org.allaymc.api.container.ContainerType;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.container.interfaces.RecipeContainer;
import org.allaymc.api.eventbus.EventBus;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.type.ItemTypes;
import org.allaymc.api.server.Server;
import org.allaymc.server.entity.component.player.EntityPlayerContainerHolderComponentImpl;
import org.allaymc.server.entity.impl.EntityPlayerImpl;
import org.allaymc.server.network.processor.ingame.ItemStackRequestPacketProcessor;
import org.allaymc.server.player.AllayPlayer;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.IdentifiableRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.NetworkRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class CraftingRequestTest {

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void transmittedRecipeWorksInGridAndTableWithManualAndAutoCraft(boolean table, boolean auto) {
        var protocol = CraftingDataWireTest.protocol(2193);
        var recipeId = table ? "minecraft:wooden_pickaxe" : "minecraft:oak_planks";
        var transmitted = CraftingDataWireTest.recipes(CraftingDataWireTest.roundTrip(
                protocol, protocol.getEncoder().encodeCraftingData()));
        var recipe = transmitted.stream()
                .filter(value -> value instanceof IdentifiableRecipeData identifiable && identifiable.getId().equals(recipeId))
                .findFirst().orElseThrow();
        int networkId = assertInstanceOf(NetworkRecipeData.class, recipe).getNetId();

        var entity = mock(EntityPlayerImpl.class);
        var holder = new EntityPlayerContainerHolderComponentImpl();
        when(entity.getContainerHolderComponent()).thenReturn(holder);
        doAnswer(call -> holder.getContainer((ContainerType<?>) call.getArgument(0)))
                .when(entity).getContainer(any(ContainerType.class));
        RecipeContainer grid = table ? holder.getContainer(ContainerTypes.CRAFTING_TABLE)
                : holder.getContainer(ContainerTypes.CRAFTING_GRID);
        var inventory = holder.getContainer(ContainerTypes.INVENTORY);
        var player = mock(AllayPlayer.class);
        when(player.getControlledEntity()).thenReturn(entity);
        when(player.getProtocol()).thenReturn(protocol);
        when(player.getOriginName()).thenReturn("Crafter");
        when(player.getOpenedContainer(ContainerTypes.CRAFTING_TABLE))
                .thenReturn(table ? holder.getContainer(ContainerTypes.CRAFTING_TABLE) : null);
        doReturn(grid).when(player).getOpenedContainer(ContainerSlotType.CRAFTING_INPUT);

        var actions = new ArrayList<ItemStackRequestAction>();
        int crafts = table ? 1 : 2;
        actions.add(auto ? new AutoCraftRecipeAction(networkId, crafts, List.of(), crafts) : new CraftRecipeAction(networkId, crafts));
        var consumedSlots = new ArrayList<Integer>();
        Container source = auto ? inventory : grid;
        if (table && auto) {
            addIngredient(source, 1, ItemTypes.OAK_PLANKS.createItemStack(3), true, true, actions, consumedSlots);
            addIngredient(source, 2, ItemTypes.STICK.createItemStack(2), true, true, actions, consumedSlots);
        } else if (table) {
            for (int slot : new int[]{0, 1, 2}) {
                addIngredient(source, slot, ItemTypes.OAK_PLANKS.createItemStack(1), true, false, actions, consumedSlots);
            }
            for (int slot : new int[]{4, 7}) {
                addIngredient(source, slot, ItemTypes.STICK.createItemStack(1), true, false, actions, consumedSlots);
            }
        } else {
            addIngredient(source, auto ? 1 : 0, ItemTypes.OAK_LOG.createItemStack(2), false, auto, actions, consumedSlots);
        }
        int outputCount = table ? 1 : 8;
        actions.add(new TakeAction(outputCount, slot(ContainerSlotType.CREATED_OUTPUT, 50, -7),
                slot(ContainerSlotType.HOTBAR_AND_INVENTORY, 0, 0)));
        var request = new ItemStackRequestPacket();
        request.getRequests().add(new ItemStackRequest(-7, actions.toArray(ItemStackRequestAction[]::new), new String[0], null));

        try (var serverAccess = mockStatic(Server.class)) {
            var server = mock(Server.class);
            when(server.getEventBus()).thenReturn(mock(EventBus.class));
            serverAccess.when(Server::getInstance).thenReturn(server);
            new ItemStackRequestPacketProcessor().handleSync(player, request, 0);
        }

        var response = ArgumentCaptor.forClass(BedrockPacket.class);
        verify(player).sendPacket(response.capture());
        var packet = assertInstanceOf(ItemStackResponsePacket.class, response.getValue());
        assertEquals(ItemStackResponseStatus.OK, packet.getEntries().getFirst().result());
        assertEquals(table ? ItemTypes.WOODEN_PICKAXE : ItemTypes.OAK_PLANKS, inventory.getItemStack(0).getItemType());
        assertEquals(outputCount, inventory.getItemStack(0).getCount());
        for (int slot : consumedSlots) {
            assertTrue(source.isEmpty(slot));
        }
        assertTrue(holder.getContainer(ContainerTypes.CREATED_OUTPUT).isEmpty());
    }

    private static void addIngredient(Container source, int index, ItemStack item, boolean table, boolean auto,
                                      List<ItemStackRequestAction> actions, List<Integer> consumedSlots) {
        source.setItemStack(index, item, false);
        int networkSlot = auto ? index : index + (table ? 32 : 28);
        actions.add(new ConsumeAction(item.getCount(), slot(
                auto ? ContainerSlotType.HOTBAR_AND_INVENTORY : ContainerSlotType.CRAFTING_INPUT,
                networkSlot, item.getUniqueId())));
        consumedSlots.add(index);
    }

    private static ItemStackRequestSlotData slot(ContainerSlotType type, int index, int id) {
        return new ItemStackRequestSlotData(type, index, id, new FullContainerName(type, null));
    }
}
