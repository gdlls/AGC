package io.papermc.paper.plugin;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Normal
public class PluginCompatibilityTest {

    private Plugin plugin;

    @BeforeEach
    public void setUp() {
        plugin = new PaperTestPlugin("compatibility-test");
    }

    @AfterEach
    public void tearDown() {
        Bukkit.getPluginManager().clearPlugins();
    }

    // 1. Event Order Test
    @Test
    public void testEventPriorityOrdering() {
        // BlockBreakEvent
        BlockBreakOrderListener breakListener = new BlockBreakOrderListener();
        Bukkit.getPluginManager().registerEvents(breakListener, plugin);
        BlockBreakEvent breakEvent = new BlockBreakEvent(mock(Block.class), mock(Player.class));
        Bukkit.getPluginManager().callEvent(breakEvent);
        assertEquals(List.of(
            EventPriority.LOWEST, EventPriority.LOW, EventPriority.NORMAL,
            EventPriority.HIGH, EventPriority.HIGHEST, EventPriority.MONITOR
        ), breakListener.executionOrder);

        // BlockPlaceEvent
        BlockPlaceOrderListener placeListener = new BlockPlaceOrderListener();
        Bukkit.getPluginManager().registerEvents(placeListener, plugin);
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(
            mock(Block.class), mock(BlockState.class), mock(Block.class),
            mock(ItemStack.class), mock(Player.class), true, EquipmentSlot.HAND
        );
        Bukkit.getPluginManager().callEvent(placeEvent);
        assertEquals(List.of(
            EventPriority.LOWEST, EventPriority.LOW, EventPriority.NORMAL,
            EventPriority.HIGH, EventPriority.HIGHEST, EventPriority.MONITOR
        ), placeListener.executionOrder);

        // EntityDamageEvent
        EntityDamageOrderListener damageListener = new EntityDamageOrderListener();
        Bukkit.getPluginManager().registerEvents(damageListener, plugin);
        EntityDamageEvent damageEvent = new EntityDamageEvent(
            mock(Entity.class), DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 10.0
        );
        Bukkit.getPluginManager().callEvent(damageEvent);
        assertEquals(List.of(
            EventPriority.LOWEST, EventPriority.LOW, EventPriority.NORMAL,
            EventPriority.HIGH, EventPriority.HIGHEST, EventPriority.MONITOR
        ), damageListener.executionOrder);

        // EntityDamageByEntityEvent
        EntityDamageByEntityOrderListener damageByEntityListener = new EntityDamageByEntityOrderListener();
        Bukkit.getPluginManager().registerEvents(damageByEntityListener, plugin);
        EntityDamageByEntityEvent damageByEntityEvent = new EntityDamageByEntityEvent(
            mock(Entity.class), mock(Entity.class), DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 10.0
        );
        Bukkit.getPluginManager().callEvent(damageByEntityEvent);
        assertEquals(List.of(
            EventPriority.LOWEST, EventPriority.LOW, EventPriority.NORMAL,
            EventPriority.HIGH, EventPriority.HIGHEST, EventPriority.MONITOR
        ), damageByEntityListener.executionOrder);
    }

    // 2. Event Cancellation Test
    @Test
    public void testEventCancellation() {
        // BlockBreakEvent Cancellation
        BlockBreakCancellationListener breakListener = new BlockBreakCancellationListener();
        Bukkit.getPluginManager().registerEvents(breakListener, plugin);
        BlockBreakEvent breakEvent = new BlockBreakEvent(mock(Block.class), mock(Player.class));
        Bukkit.getPluginManager().callEvent(breakEvent);
        assertTrue(breakEvent.isCancelled());
        assertEquals(List.of(false, true, true, true, true, true), breakListener.cancelledStates);

        // BlockPlaceEvent Cancellation
        BlockPlaceCancellationListener placeListener = new BlockPlaceCancellationListener();
        Bukkit.getPluginManager().registerEvents(placeListener, plugin);
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(
            mock(Block.class), mock(BlockState.class), mock(Block.class),
            mock(ItemStack.class), mock(Player.class), true, EquipmentSlot.HAND
        );
        Bukkit.getPluginManager().callEvent(placeEvent);
        assertTrue(placeEvent.isCancelled());
        assertEquals(List.of(false, true, true, true, true, true), placeListener.cancelledStates);

        // EntityDamageEvent Cancellation
        EntityDamageCancellationListener damageListener = new EntityDamageCancellationListener();
        Bukkit.getPluginManager().registerEvents(damageListener, plugin);
        EntityDamageEvent damageEvent = new EntityDamageEvent(
            mock(Entity.class), DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 10.0
        );
        Bukkit.getPluginManager().callEvent(damageEvent);
        assertTrue(damageEvent.isCancelled());
        assertEquals(List.of(false, true, true, true, true, true), damageListener.cancelledStates);

        // EntityDamageByEntityEvent Cancellation
        EntityDamageByEntityCancellationListener damageByEntityListener = new EntityDamageByEntityCancellationListener();
        Bukkit.getPluginManager().registerEvents(damageByEntityListener, plugin);
        EntityDamageByEntityEvent damageByEntityEvent = new EntityDamageByEntityEvent(
            mock(Entity.class), mock(Entity.class), DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 10.0
        );
        Bukkit.getPluginManager().callEvent(damageByEntityEvent);
        assertTrue(damageByEntityEvent.isCancelled());
        assertEquals(List.of(false, true, true, true, true, true), damageByEntityListener.cancelledStates);
    }

    // 3. Execution Thread Test
    @Test
    public void testExecutionThread() {
        // Since we are running synchronously in this thread, all event handlers should report Bukkit.isPrimaryThread() == true
        // using the setup from DummyServerHelper.
        BlockBreakThreadListener breakListener = new BlockBreakThreadListener();
        Bukkit.getPluginManager().registerEvents(breakListener, plugin);
        BlockBreakEvent breakEvent = new BlockBreakEvent(mock(Block.class), mock(Player.class));
        Bukkit.getPluginManager().callEvent(breakEvent);
        for (boolean isPrimary : breakListener.primaryThreadStatus) {
            assertTrue(isPrimary, "Event handler must execute on primary thread");
        }
    }

    // 4. Event Mutability Test
    @Test
    public void testEventMutability() {
        // BlockBreakEvent mutability: setDropItems
        BlockBreakMutabilityListener breakListener = new BlockBreakMutabilityListener();
        Bukkit.getPluginManager().registerEvents(breakListener, plugin);
        BlockBreakEvent breakEvent = new BlockBreakEvent(mock(Block.class), mock(Player.class));
        breakEvent.setDropItems(true);
        Bukkit.getPluginManager().callEvent(breakEvent);
        assertFalse(breakEvent.isDropItems());
        assertEquals(List.of(true, false, false, false, false, false), breakListener.dropItemsValues);

        // BlockPlaceEvent mutability: setBuild
        BlockPlaceMutabilityListener placeListener = new BlockPlaceMutabilityListener();
        Bukkit.getPluginManager().registerEvents(placeListener, plugin);
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(
            mock(Block.class), mock(BlockState.class), mock(Block.class),
            mock(ItemStack.class), mock(Player.class), true, EquipmentSlot.HAND
        );
        Bukkit.getPluginManager().callEvent(placeEvent);
        assertFalse(placeEvent.canBuild());
        assertEquals(List.of(true, false, false, false, false, false), placeListener.canBuildValues);

        // EntityDamageEvent mutability: setDamage
        EntityDamageMutabilityListener damageListener = new EntityDamageMutabilityListener();
        Bukkit.getPluginManager().registerEvents(damageListener, plugin);
        EntityDamageEvent damageEvent = new EntityDamageEvent(
            mock(Entity.class), DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 10.0
        );
        Bukkit.getPluginManager().callEvent(damageEvent);
        assertEquals(20.0, damageEvent.getDamage());
        assertEquals(List.of(10.0, 20.0, 20.0, 20.0, 20.0, 20.0), damageListener.damageValues);

        // EntityDamageByEntityEvent mutability: setDamage
        EntityDamageByEntityMutabilityListener damageByEntityListener = new EntityDamageByEntityMutabilityListener();
        Bukkit.getPluginManager().registerEvents(damageByEntityListener, plugin);
        EntityDamageByEntityEvent damageByEntityEvent = new EntityDamageByEntityEvent(
            mock(Entity.class), mock(Entity.class), DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 10.0
        );
        Bukkit.getPluginManager().callEvent(damageByEntityEvent);
        assertEquals(20.0, damageByEntityEvent.getDamage());
        assertEquals(List.of(10.0, 20.0, 20.0, 20.0, 20.0, 20.0), damageByEntityListener.damageValues);
    }

    // --- Order Listeners ---
    public static class BlockBreakOrderListener implements Listener {
        public final List<EventPriority> executionOrder = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(BlockBreakEvent e) { executionOrder.add(EventPriority.LOWEST); }
        @EventHandler(priority = EventPriority.LOW) public void o2(BlockBreakEvent e) { executionOrder.add(EventPriority.LOW); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(BlockBreakEvent e) { executionOrder.add(EventPriority.NORMAL); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(BlockBreakEvent e) { executionOrder.add(EventPriority.HIGH); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(BlockBreakEvent e) { executionOrder.add(EventPriority.HIGHEST); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(BlockBreakEvent e) { executionOrder.add(EventPriority.MONITOR); }
    }

    public static class BlockPlaceOrderListener implements Listener {
        public final List<EventPriority> executionOrder = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(BlockPlaceEvent e) { executionOrder.add(EventPriority.LOWEST); }
        @EventHandler(priority = EventPriority.LOW) public void o2(BlockPlaceEvent e) { executionOrder.add(EventPriority.LOW); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(BlockPlaceEvent e) { executionOrder.add(EventPriority.NORMAL); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(BlockPlaceEvent e) { executionOrder.add(EventPriority.HIGH); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(BlockPlaceEvent e) { executionOrder.add(EventPriority.HIGHEST); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(BlockPlaceEvent e) { executionOrder.add(EventPriority.MONITOR); }
    }

    public static class EntityDamageOrderListener implements Listener {
        public final List<EventPriority> executionOrder = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(EntityDamageEvent e) { executionOrder.add(EventPriority.LOWEST); }
        @EventHandler(priority = EventPriority.LOW) public void o2(EntityDamageEvent e) { executionOrder.add(EventPriority.LOW); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(EntityDamageEvent e) { executionOrder.add(EventPriority.NORMAL); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(EntityDamageEvent e) { executionOrder.add(EventPriority.HIGH); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(EntityDamageEvent e) { executionOrder.add(EventPriority.HIGHEST); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(EntityDamageEvent e) { executionOrder.add(EventPriority.MONITOR); }
    }

    public static class EntityDamageByEntityOrderListener implements Listener {
        public final List<EventPriority> executionOrder = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(EntityDamageByEntityEvent e) { executionOrder.add(EventPriority.LOWEST); }
        @EventHandler(priority = EventPriority.LOW) public void o2(EntityDamageByEntityEvent e) { executionOrder.add(EventPriority.LOW); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(EntityDamageByEntityEvent e) { executionOrder.add(EventPriority.NORMAL); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(EntityDamageByEntityEvent e) { executionOrder.add(EventPriority.HIGH); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(EntityDamageByEntityEvent e) { executionOrder.add(EventPriority.HIGHEST); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(EntityDamageByEntityEvent e) { executionOrder.add(EventPriority.MONITOR); }
    }

    // --- Cancellation Listeners ---
    public static class BlockBreakCancellationListener implements Listener {
        public final List<Boolean> cancelledStates = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(BlockBreakEvent e) { cancelledStates.add(e.isCancelled()); e.setCancelled(true); }
        @EventHandler(priority = EventPriority.LOW) public void o2(BlockBreakEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(BlockBreakEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(BlockBreakEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(BlockBreakEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(BlockBreakEvent e) { cancelledStates.add(e.isCancelled()); }
    }

    public static class BlockPlaceCancellationListener implements Listener {
        public final List<Boolean> cancelledStates = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(BlockPlaceEvent e) { cancelledStates.add(e.isCancelled()); e.setCancelled(true); }
        @EventHandler(priority = EventPriority.LOW) public void o2(BlockPlaceEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(BlockPlaceEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(BlockPlaceEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(BlockPlaceEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(BlockPlaceEvent e) { cancelledStates.add(e.isCancelled()); }
    }

    public static class EntityDamageCancellationListener implements Listener {
        public final List<Boolean> cancelledStates = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(EntityDamageEvent e) { cancelledStates.add(e.isCancelled()); e.setCancelled(true); }
        @EventHandler(priority = EventPriority.LOW) public void o2(EntityDamageEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(EntityDamageEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(EntityDamageEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(EntityDamageEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(EntityDamageEvent e) { cancelledStates.add(e.isCancelled()); }
    }

    public static class EntityDamageByEntityCancellationListener implements Listener {
        public final List<Boolean> cancelledStates = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(EntityDamageByEntityEvent e) { cancelledStates.add(e.isCancelled()); e.setCancelled(true); }
        @EventHandler(priority = EventPriority.LOW) public void o2(EntityDamageByEntityEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(EntityDamageByEntityEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(EntityDamageByEntityEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(EntityDamageByEntityEvent e) { cancelledStates.add(e.isCancelled()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(EntityDamageByEntityEvent e) { cancelledStates.add(e.isCancelled()); }
    }

    // --- Thread Listeners ---
    public static class BlockBreakThreadListener implements Listener {
        public final List<Boolean> primaryThreadStatus = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(BlockBreakEvent e) { primaryThreadStatus.add(Bukkit.isPrimaryThread()); }
        @EventHandler(priority = EventPriority.LOW) public void o2(BlockBreakEvent e) { primaryThreadStatus.add(Bukkit.isPrimaryThread()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(BlockBreakEvent e) { primaryThreadStatus.add(Bukkit.isPrimaryThread()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(BlockBreakEvent e) { primaryThreadStatus.add(Bukkit.isPrimaryThread()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(BlockBreakEvent e) { primaryThreadStatus.add(Bukkit.isPrimaryThread()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(BlockBreakEvent e) { primaryThreadStatus.add(Bukkit.isPrimaryThread()); }
    }

    // --- Mutability Listeners ---
    public static class BlockBreakMutabilityListener implements Listener {
        public final List<Boolean> dropItemsValues = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(BlockBreakEvent e) { dropItemsValues.add(e.isDropItems()); e.setDropItems(false); }
        @EventHandler(priority = EventPriority.LOW) public void o2(BlockBreakEvent e) { dropItemsValues.add(e.isDropItems()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(BlockBreakEvent e) { dropItemsValues.add(e.isDropItems()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(BlockBreakEvent e) { dropItemsValues.add(e.isDropItems()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(BlockBreakEvent e) { dropItemsValues.add(e.isDropItems()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(BlockBreakEvent e) { dropItemsValues.add(e.isDropItems()); }
    }

    public static class BlockPlaceMutabilityListener implements Listener {
        public final List<Boolean> canBuildValues = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(BlockPlaceEvent e) { canBuildValues.add(e.canBuild()); e.setBuild(false); }
        @EventHandler(priority = EventPriority.LOW) public void o2(BlockPlaceEvent e) { canBuildValues.add(e.canBuild()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(BlockPlaceEvent e) { canBuildValues.add(e.canBuild()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(BlockPlaceEvent e) { canBuildValues.add(e.canBuild()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(BlockPlaceEvent e) { canBuildValues.add(e.canBuild()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(BlockPlaceEvent e) { canBuildValues.add(e.canBuild()); }
    }

    public static class EntityDamageMutabilityListener implements Listener {
        public final List<Double> damageValues = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(EntityDamageEvent e) { damageValues.add(e.getDamage()); e.setDamage(20.0); }
        @EventHandler(priority = EventPriority.LOW) public void o2(EntityDamageEvent e) { damageValues.add(e.getDamage()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(EntityDamageEvent e) { damageValues.add(e.getDamage()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(EntityDamageEvent e) { damageValues.add(e.getDamage()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(EntityDamageEvent e) { damageValues.add(e.getDamage()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(EntityDamageEvent e) { damageValues.add(e.getDamage()); }
    }

    public static class EntityDamageByEntityMutabilityListener implements Listener {
        public final List<Double> damageValues = new ArrayList<>();
        @EventHandler(priority = EventPriority.LOWEST) public void o1(EntityDamageByEntityEvent e) { damageValues.add(e.getDamage()); e.setDamage(20.0); }
        @EventHandler(priority = EventPriority.LOW) public void o2(EntityDamageByEntityEvent e) { damageValues.add(e.getDamage()); }
        @EventHandler(priority = EventPriority.NORMAL) public void o3(EntityDamageByEntityEvent e) { damageValues.add(e.getDamage()); }
        @EventHandler(priority = EventPriority.HIGH) public void o4(EntityDamageByEntityEvent e) { damageValues.add(e.getDamage()); }
        @EventHandler(priority = EventPriority.HIGHEST) public void o5(EntityDamageByEntityEvent e) { damageValues.add(e.getDamage()); }
        @EventHandler(priority = EventPriority.MONITOR) public void o6(EntityDamageByEntityEvent e) { damageValues.add(e.getDamage()); }
    }
}
