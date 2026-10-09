package org.allaymc.server.network.processor.ingame;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.player.Player;
import org.allaymc.server.container.processor.ActionResponse;
import org.allaymc.server.container.processor.ContainerActionProcessor;
import org.allaymc.server.container.processor.ContainerActionProcessorHolder;
import org.allaymc.server.network.processor.PacketProcessor;
import org.allaymc.server.player.AllayPlayer;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;

import java.util.*;

import static org.allaymc.server.container.processor.CraftRecipeOptionalActionProcessor.FILTER_STRINGS_DATA_KEY;

@Slf4j
public class ItemStackRequestPacketProcessor extends PacketProcessor<ItemStackRequestPacket> {
    protected final ContainerActionProcessorHolder processorHolder = new ContainerActionProcessorHolder();

    @Override
    public void handleSync(Player player, ItemStackRequestPacket packet, long receiveTime) {
        log.debug("[DEBUG-STACK-REQUEST] player={} receiveTime={} requestCount={}",
                player.getOriginName(), receiveTime, packet.getRequests().size());

        List<ItemStackResponse> encodedResponses = new LinkedList<>();

        label:
        for (var request : packet.getRequests()) {
            log.debug("[DEBUG-STACK-REQUEST] REQUEST id={} actions={} filters={} origin={}",
                    request.requestId(),
                    request.actions().length,
                    Arrays.toString(request.filterStrings()),
                    request.textProcessingEventOrigin());

            List<ActionResponse> responses = new LinkedList<>();
            var noResponseForDestroyAction = false;
            var actions = request.actions();

            for (int index = 0; index < actions.length; index++) {
                var action = actions[index];
                if (processorHolder.getProcessor(action.getType()) == null) {
                    log.warn("Rejecting unsupported item stack action type {} (request id={}, action[{}])",
                            action.getType(), request.requestId(), index);
                    encodedResponses.add(new ItemStackResponse(
                            ItemStackResponseStatus.INVALID_REQUEST_ACTION_TYPE,
                            request.requestId(),
                            Collections.emptyList()
                    ));
                    continue label;
                }
            }

            Map<String, Object> dataPool = new HashMap<>();
            dataPool.put(FILTER_STRINGS_DATA_KEY, request.filterStrings());

            for (int index = 0; index < actions.length; index++) {
                var action = actions[index];

                log.debug("[DEBUG-STACK-REQUEST]   action[{}] type={} data={}",
                        index, action.getType(), action);

                if (action.getType() == ItemStackRequestActionType.CRAFT_RESULTS_DEPRECATED) {
                    noResponseForDestroyAction = true;
                }

                ContainerActionProcessor<ItemStackRequestAction> processor = processorHolder.getProcessor(action.getType());
                ActionResponse response;
                try {
                    response = processor.handle(action, player, index, actions, dataPool);
                } catch (RuntimeException exception) {
                    log.error("Rejecting failed item stack action[{}] type={} request id={}: data={}",
                            index, action.getType(), request.requestId(), action, exception);
                    encodedResponses.add(new ItemStackResponse(
                            ItemStackResponseStatus.ITEM_REQUEST_ACTION_HANDLER_COMMIT_FAILED,
                            request.requestId(),
                            Collections.emptyList()
                    ));
                    continue label;
                }

                log.debug("[DEBUG-STACK-REQUEST]   action[{}] processor={} response={}",
                        index,
                        processor.getClass().getSimpleName(),
                        response);

                if (response == null) {
                    continue;
                }

                if (!response.ok()) {
                    ItemStackResponse errorResponse =
                            new ItemStackResponse(ItemStackResponseStatus.ERROR, request.requestId(), Collections.emptyList());
                    encodedResponses.add(errorResponse);
                    log.warn("Item stack request id={} rejected by action[{}] type={} (player={})",
                            request.requestId(), index, action.getType(), player.getOriginName());
                    continue label;
                }

                if (noResponseForDestroyAction && action.getType() == ItemStackRequestActionType.DESTROY) {
                    noResponseForDestroyAction = false;
                } else {
                    responses.add(response);
                }
            }

            ItemStackResponse encoded = encodeActionResponses(responses, request.requestId());
            log.debug("[DEBUG-STACK-REQUEST] request id={} -> result={} containers={}",
                    request.requestId(), encoded.result(), encoded.containers().size());
            encodedResponses.add(encoded);
        }

        var allayPlayer = (AllayPlayer) player;
        var responsePacket = allayPlayer.getProtocol().getEncoder().encodeItemStackResponse(encodedResponses);

        log.debug("[DEBUG-STACK-REQUEST] sending ItemStackResponsePacket entries={}", responsePacket.getEntries().size());

        allayPlayer.sendPacket(responsePacket);
    }

    private ItemStackResponse encodeActionResponses(List<ActionResponse> responses, int requestId) {
        Map<ContainerSlotType, Int2ObjectMap<ItemStackResponseSlot>> changedContainers = new HashMap<>();

        responses.forEach(response -> response.containers().forEach(container -> {
            log.debug("[DEBUG-STACK-REQUEST]     changed container={} items={}",
                    container.container(), container.items());

            for (var changedSlot : container.items()) {
                var changedSlots = changedContainers.computeIfAbsent(
                        container.container(),
                        $ -> new Int2ObjectOpenHashMap<>()
                );
                changedSlots.put(changedSlot.getSlot(), changedSlot);
            }
        }));

        var containers = changedContainers.entrySet().stream()
                .map(entry -> new ItemStackResponseContainer(
                        entry.getKey(),
                        new ArrayList<>(entry.getValue().values()),
                        new FullContainerName(entry.getKey(), null)
                ))
                .toList();

        return new ItemStackResponse(ItemStackResponseStatus.OK, requestId, containers);
    }

    @Override
    public BedrockPacketType getPacketType() {
        return BedrockPacketType.ITEM_STACK_REQUEST;
    }
}
