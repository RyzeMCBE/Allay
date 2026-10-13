package org.allaymc.server.container.processor;

import org.allaymc.server.container.AllayFakeContainerFactory;
import org.allaymc.server.container.impl.FakeChestContainerImpl;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(AllayTestExtension.class)
class EditableContainerTest {
    @Test void defaultMenusStayReadOnlyAndInvokeTheirClick() {
        var source = new FakeChestContainerImpl();
        var target = new FakeChestContainerImpl();
        var clicks = new AtomicInteger();
        source.addClickListener(0, clicks::incrementAndGet);
        assertTrue(source.isReadOnly());
        assertTrue(ContainerActionProcessor.tryHandleFakeContainer(source, 0, target, 1));
        assertEquals(1, clicks.get());
    }
    @Test void editingRequiresBothInvolvedMenusToAllowTransfers() {
        var source = new FakeChestContainerImpl();
        var target = new FakeChestContainerImpl();
        source.setReadOnly(false);
        assertTrue(ContainerActionProcessor.tryHandleFakeContainer(source, 0, target, 1));
        target.setReadOnly(false);
        assertFalse(ContainerActionProcessor.tryHandleFakeContainer(source, 0, target, 1));
    }
    @Test void hopperEditorHasFiveSlotsAndStartsReadOnly() {
        var hopper = new AllayFakeContainerFactory().createFakeHopperContainer();
        assertEquals(5, hopper.getContainerType().getSize());
        assertTrue(hopper.isReadOnly());
        hopper.setReadOnly(false);
        assertFalse(hopper.isReadOnly());
    }
}
