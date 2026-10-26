package org.allaymc.server.network.protocol;

import io.netty.buffer.Unpooled;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.*;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(AllayTestExtension.class)
class CraftingDataWireTest {

    @ParameterizedTest
    @ValueSource(ints = {818, 944, 975, 1001, 2168, 2169, 2193})
    void allRecipesAndNetworkIdsSurviveTheClientCodec(int version) {
        var protocol = protocol(version);
        var source = protocol.getData().recipeTable();
        var decoded = roundTrip(protocol, protocol.getEncoder().encodeCraftingData());
        var received = recipes(decoded);

        assertFalse(received.isEmpty());
        assertEquals(source.encodedRecipes().size(), received.size());
        assertTrue(decoded.isCleanRecipes());
        assertEquals(source.potionMixes(), decoded.getPotionMixData());
        if (version >= 2168) {
            assertTrue(decoded.getCraftingData().isEmpty());
            assertFalse(decoded.getShapedData().isEmpty());
            assertFalse(decoded.getShapelessData().isEmpty());
            assertFalse(decoded.getMultiData().isEmpty());
            assertFalse(decoded.getSmithingTransformData().isEmpty());
            assertFalse(decoded.getSmithingTrimData().isEmpty());
        } else {
            assertEquals(source.encodedRecipes().size(), decoded.getCraftingData().size());
            assertTrue(decoded.getShapedData().isEmpty());
            assertTrue(decoded.getShapelessData().isEmpty());
        }

        var expectedById = new HashMap<Integer, RecipeData>();
        for (var recipe : source.encodedRecipes()) {
            if (recipe instanceof NetworkRecipeData network) {
                assertNull(expectedById.put(network.getNetId(), recipe));
            }
        }
        var receivedIds = new HashSet<Integer>();
        for (var recipe : received) {
            if (recipe instanceof NetworkRecipeData network) {
                assertTrue(receivedIds.add(network.getNetId()), "duplicate recipe network ID");
                var expected = expectedById.get(network.getNetId());
                assertNotNull(expected);
                assertRecipeEquals(expected, recipe);
                if (recipe instanceof IdentifiableRecipeData identifiable) {
                    assertEquals(source.recipesByNetworkId().get(network.getNetId()).getIdentifier().toString(), identifiable.getId());
                }
            }
        }
        assertEquals(expectedById.keySet(), receivedIds);

        var expectedFurnaces = source.encodedRecipes().stream().filter(FurnaceRecipeData.class::isInstance).toList();
        var receivedFurnaces = received.stream().filter(FurnaceRecipeData.class::isInstance).toList();
        assertEquals(expectedFurnaces.size(), receivedFurnaces.size());
        for (int i = 0; i < expectedFurnaces.size(); i++) {
            assertRecipeEquals(expectedFurnaces.get(i), receivedFurnaces.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2168, 2169, 2193})
    void modernPacketsOwnTheirListsAndRecipePayloads(int version) {
        var protocol = protocol(version);
        var first = protocol.getEncoder().encodeCraftingData();
        var second = protocol.getEncoder().encodeCraftingData();
        var count = recipes(second).size();
        var ingredients = List.copyOf(second.getShapedData().getFirst().getIngredients());
        var outputs = List.copyOf(second.getShapedData().getFirst().getResults());

        first.getShapedData().getFirst().getIngredients().clear();
        first.getShapedData().getFirst().getResults().clear();
        first.getShapedData().clear();
        first.getShapelessData().clear();
        first.getPotionMixData().clear();

        assertEquals(count, recipes(second).size());
        assertEquals(ingredients, second.getShapedData().getFirst().getIngredients());
        assertEquals(outputs, second.getShapedData().getFirst().getResults());
        var third = roundTrip(protocol, protocol.getEncoder().encodeCraftingData());
        assertEquals(count, recipes(third).size());
        assertEquals(protocol.getData().recipeTable().potionMixes(), third.getPotionMixData());
    }

    static Protocol protocol(int version) {
        var protocol = ProtocolRegistry.getDefault().resolve(ClientVariant.INTERNATIONAL, version);
        assertNotNull(protocol);
        return protocol;
    }

    static CraftingDataPacket roundTrip(Protocol protocol, CraftingDataPacket packet) {
        var helper = protocol.getCodec().createHelper();
        helper.setEncodingSettings(EncodingSettings.CLIENT);
        helper.setItemDefinitions(protocol.getItemDefinitionRegistry());
        helper.setBlockDefinitions(protocol.getBlockDefinitionRegistry());
        var serializer = protocol.getCodec().getPacketDefinition(CraftingDataPacket.class).serializer();
        var buffer = Unpooled.buffer();
        try {
            serializer.serialize(buffer, helper, packet);
            var decoded = new CraftingDataPacket();
            serializer.deserialize(buffer, helper, decoded);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    static List<RecipeData> recipes(CraftingDataPacket packet) {
        var recipes = new ArrayList<RecipeData>();
        recipes.addAll(packet.getCraftingData());
        recipes.addAll(packet.getShapedData());
        recipes.addAll(packet.getShapelessData());
        recipes.addAll(packet.getMultiData());
        recipes.addAll(packet.getShapelessUserData());
        recipes.addAll(packet.getShapelessChemistryData());
        recipes.addAll(packet.getShapedChemistryData());
        recipes.addAll(packet.getSmithingTransformData());
        recipes.addAll(packet.getSmithingTrimData());
        return recipes;
    }

    private static void assertRecipeEquals(RecipeData expected, RecipeData actual) {
        assertEquals(expected.getType(), actual.getType());
        if (expected instanceof UniqueCraftingData unique) {
            assertEquals(unique.getUuid(), assertInstanceOf(UniqueCraftingData.class, actual).getUuid());
        }
        if (expected instanceof TaggedCraftingData tagged) {
            assertEquals(tagged.getTag(), assertInstanceOf(TaggedCraftingData.class, actual).getTag());
        }
        switch (expected) {
            case CraftingRecipeData crafting -> {
                var decoded = assertInstanceOf(CraftingRecipeData.class, actual);
                assertEquals(crafting.getId(), decoded.getId());
                assertEquals(crafting.getIngredients(), decoded.getIngredients());
                assertEquals(crafting.getPriority(), decoded.getPriority());
                assertEquals(crafting.getRequirement(), decoded.getRequirement());
                assertEquals(crafting.getResults().size(), decoded.getResults().size());
                for (int i = 0; i < crafting.getResults().size(); i++) {
                    assertOutputEquals(crafting.getResults().get(i), decoded.getResults().get(i));
                }
                if (crafting instanceof ShapedRecipeData shaped) {
                    var decodedShaped = assertInstanceOf(ShapedRecipeData.class, actual);
                    assertEquals(shaped.getWidth(), decodedShaped.getWidth());
                    assertEquals(shaped.getHeight(), decodedShaped.getHeight());
                    assertEquals(shaped.isAssumeSymetry(), decodedShaped.isAssumeSymetry());
                }
            }
            case SmithingTransformRecipeData smithing -> {
                var decoded = assertInstanceOf(SmithingTransformRecipeData.class, actual);
                assertEquals(smithing.getBase(), decoded.getBase());
                assertEquals(smithing.getAddition(), decoded.getAddition());
                assertEquals(smithing.getTemplate(), decoded.getTemplate());
                assertOutputEquals(smithing.getResult(), decoded.getResult());
            }
            case SmithingTrimRecipeData ignored -> assertEquals(expected, actual);
            case FurnaceRecipeData furnace -> {
                var decoded = assertInstanceOf(FurnaceRecipeData.class, actual);
                assertEquals(furnace.getInputId(), decoded.getInputId());
                assertEquals(furnace.getInputData(), decoded.getInputData());
                assertOutputEquals(furnace.getResult(), decoded.getResult());
            }
            case MultiRecipeData ignored -> assertEquals(expected, actual);
            default -> fail("Unexpected recipe type: " + expected.getType());
        }
    }

    private static void assertOutputEquals(ItemData expected, ItemData actual) {
        assertEquals(expected.getDefinition(), actual.getDefinition());
        assertEquals(expected.getCount(), actual.getCount());
        assertEquals(expected.getDamage(), actual.getDamage());
        assertEquals(expected.getTag(), actual.getTag());
        assertEquals(expected.getBlockDefinition() == null ? 0 : expected.getBlockDefinition().runtimeId(),
                actual.getBlockDefinition() == null ? 0 : actual.getBlockDefinition().runtimeId());
    }
}
