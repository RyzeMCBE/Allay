package org.allaymc.api.eventbus.event.entity;

import lombok.Getter;
import lombok.Setter;
import org.allaymc.api.math.location.Location3d;
import org.allaymc.api.annotation.CallerThread;
import org.allaymc.api.annotation.ThreadType;
import org.allaymc.api.entity.Entity;
import org.allaymc.api.eventbus.event.CancellableEvent;

/**
 * Called when an entity is about to be teleported by a portal.
 *
 * @author daoge_cmd
 */
@Getter
@CallerThread(ThreadType.DIMENSION)
public class EntityPortalEnterEvent extends EntityEvent implements CancellableEvent {

    /**
     * The type of portal being entered.
     */
    protected PortalType portalType;

    /**
     * Optional exact arrival or portal-generation coordinates for the destination
     * dimension. Plugins may set this during the event; when null, the server
     * keeps vanilla Nether coordinate scaling / POI search or End spawn handling.
     *
     * <p>The dimension must be the target for the current portal transition
     * (Overworld ↔ Nether, or Overworld ↔ End). Invalid targets abort safely.</p>
     * <p>For Nether, X/Y/Z refer to a portal interior block: if there is no
     * nearby existing portal, the server only builds at these coordinates when
     * the complete frame can be placed without destroying custom terrain.</p>
     */
    @Setter
    protected Location3d destinationOverride;

    public EntityPortalEnterEvent(Entity entity, PortalType portalType) {
        super(entity);
        this.portalType = portalType;
    }

    /**
     * The type of portal being entered.
     */
    public enum PortalType {
        NETHER,
        END
    }
}
