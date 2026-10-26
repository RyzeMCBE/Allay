package org.allaymc.server.network.protocol.v2168;

import org.allaymc.api.player.Player;
import org.allaymc.server.network.protocol.ProtocolData;
import org.allaymc.server.network.protocol.v1001.PacketEncoder_v1001;
import org.cloudburstmc.protocol.bedrock.data.BuildPlatform;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.*;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;

import java.util.Collection;

public class PacketEncoder_v2168 extends PacketEncoder_v1001 {
    public PacketEncoder_v2168(ProtocolData data) {
        super(data);
    }

    @Override
    public CraftingDataPacket encodeCraftingData() {
        var packet = super.encodeCraftingData();
        // Since 2168 the codec writes separate recipe lists and ignores craftingData.
        // Reuse the fresh payload copies so network IDs still match this protocol's recipe table.
        for (var recipe : packet.getCraftingData()) {
            switch (recipe.getType()) {
                case SHAPED -> packet.getShapedData().add((ShapedRecipeData) recipe);
                case SHAPELESS -> packet.getShapelessData().add((ShapelessRecipeData) recipe);
                case MULTI -> packet.getMultiData().add((MultiRecipeData) recipe);
                case SHULKER_BOX -> packet.getShapelessUserData().add((ShapelessRecipeData) recipe);
                case SHAPELESS_CHEMISTRY -> packet.getShapelessChemistryData().add((ShapelessRecipeData) recipe);
                case SHAPED_CHEMISTRY -> packet.getShapedChemistryData().add((ShapedRecipeData) recipe);
                case SMITHING_TRANSFORM -> packet.getSmithingTransformData().add((SmithingTransformRecipeData) recipe);
                case SMITHING_TRIM -> packet.getSmithingTrimData().add((SmithingTrimRecipeData) recipe);
                default -> throw new IllegalStateException("Unsupported modern recipe payload: " + recipe.getType());
            }
        }
        packet.getCraftingData().clear();
        return packet;
    }

    @Override
    public PlayerListPacket encodePlayerList(Collection<? extends Player> players, boolean add, boolean trustSkins) {
        var packet = super.encodePlayerList(players, add, trustSkins);
        for (var entry : packet.getEntries()) {
            // Cereal's modern BuildPlatform enum represents Windows as Win32, without a UWP value.
            if (entry.getBuildPlatform() == BuildPlatform.UWP) {
                entry.setBuildPlatform(BuildPlatform.WIN32);
            }
        }
        return packet;
    }
}
