package io.papermc.paper.configuration;

import com.mojang.logging.LogUtils;
import io.papermc.paper.configuration.constraint.Constraints;
import io.papermc.paper.configuration.serializer.collection.map.WriteKeyBack;
import io.papermc.paper.configuration.type.number.DoubleOr;
import io.papermc.paper.configuration.type.number.IntOr;
import io.papermc.paper.util.sanitizer.ItemObfuscationBinding;
import io.papermc.paper.util.sanitizer.OversizedItemComponentSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.PostProcess;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

@SuppressWarnings({"CanBeFinal", "FieldCanBeLocal", "FieldMayBeFinal", "NotNullFieldNotInitialized", "InnerClassMayBeStatic"})
public class GlobalConfiguration extends ConfigurationPart {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int CURRENT_VERSION = 31; // (when you change the version, change the comment, so it conflicts on rebases): allow-nether property to config
    private static GlobalConfiguration instance;
    public static boolean isFirstStart = false;
    public static GlobalConfiguration get() {
        return instance;
    }

    public ChunkLoadingBasic chunkLoadingBasic;

    public class ChunkLoadingBasic extends ConfigurationPart {
        @Comment("The maximum rate in chunks per second that the server will send to any individual player. Set to -1 to disable this limit.")
        public double playerMaxChunkSendRate = 75.0;

        @Comment(
            "The maximum rate at which chunks will load for any individual player. " +
            "Note that this setting also affects chunk generations, since a chunk load is always first issued to test if a" +
            "chunk is already generated. Set to -1 to disable this limit."
        )
        public double playerMaxChunkLoadRate = 100.0;

        @Comment("The maximum rate at which chunks will generate for any individual player. Set to -1 to disable this limit.")
        public double playerMaxChunkGenerateRate = -1.0;
    }

    public ChunkLoadingAdvanced chunkLoadingAdvanced;

    public class ChunkLoadingAdvanced extends ConfigurationPart {
        @Comment(
            "Set to true if the server will match the chunk send radius that clients have configured" +
            "in their view distance settings if the client is less-than the server's send distance."
        )
        public boolean autoConfigSendDistance = true;

        @Comment(
            "Specifies the maximum amount of concurrent chunk loads that an individual player can have." +
            "Set to 0 to let the server configure it automatically per player, or set it to -1 to disable the limit."
        )
        public int playerMaxConcurrentChunkLoads = 0;

        @Comment(
            "Specifies the maximum amount of concurrent chunk generations that an individual player can have." +
            "Set to 0 to let the server configure it automatically per player, or set it to -1 to disable the limit."
        )
        public int playerMaxConcurrentChunkGenerates = 0;
    }

    public Agc agc;

    public class Agc extends ConfigurationPart {
        public Performance performance;

        public class Performance extends ConfigurationPart {
            @Comment("Reduces repeated lookups and casts in the Moonrise entity tracker tick loop. This does not change tracking rules or packet contents.")
            public boolean denseEntityTrackerFastPath = true;
            @Comment("Skips per-world debug synchronizer bookkeeping while no debug subscriptions are active. This keeps vanilla debug subscription behavior intact.")
            public boolean idleDebugSynchronizerFastPath = true;
            @Comment("Avoids scanning every player each tick for debug subscriptions until at least one player has requested a debug subscription.")
            public boolean globalDebugSubscriberFastPath = true;
            @Comment("Uses an indexed send loop for chunk block, light, and block entity broadcasts when the receiver list supports fast random access.")
            public boolean chunkBroadcastFastPath = true;
            @Comment("Removes stale entity tracker viewers with iterator removal instead of allocating snapshot lists during dense player movement.")
            public boolean entityTrackerPurgeFastPath = true;
            @Comment("Skips natural spawn state construction in worlds with no players while preserving ticking for loaded chunks.")
            public boolean emptyWorldSpawnStateFastPath = true;
            @Comment("Reuses the latency player-info packet when every online player shares the default Bukkit visibility state.")
            public boolean sharedLatencyPacketFastPath = true;
            @Comment("Builds per-player latency update visibility lists with indexed loops instead of filtered collection views.")
            public boolean latencyVisibilityUpdateFastPath = true;
            @Comment("Caches per-broadcast clock state values while sending world time updates to many players.")
            public boolean clockBroadcastFastPath = true;
            @Comment("Uses indexed loops for common PlayerList packet broadcasts to reduce iterator overhead at high player counts.")
            public boolean playerListBroadcastFastPath = true;
            @Comment("Skips the chunk broadcast profiler section when no chunks have pending block or light changes.")
            public boolean emptyChunkBroadcastFastPath = true;
            @Comment("Avoids waypoint connection table views and set-difference allocation when no players or waypoints can be affected.")
            public boolean waypointEmptyFastPath = true;
            @Comment("Uses indexed loops for common PlayerList maintenance passes over all online players.")
            public boolean playerListIterationFastPath = true;
            @Comment("Skips entity tracking packet broadcast work immediately when an entity has no tracking players.")
            public boolean emptyEntityTrackerBroadcastFastPath = true;
            @Comment("Avoids weather player update loops in empty worlds and uses indexed loops when players are present.")
            public boolean weatherPlayerLoopFastPath = true;
            @Comment("Uses indexed loops for expensive player resource reload broadcasts such as advancements and recipes.")
            public boolean playerResourceReloadFastPath = true;
            @Comment("Uses indexed loops for world border packet broadcasts and global view-distance updates.")
            public boolean playerListWorldUpdateFastPath = true;
            @Comment("Uses indexed loops for player removal visibility broadcasts.")
            public boolean playerRemoveBroadcastFastPath = true;
            @Comment("Uses indexed loops for duplicate-login checks across online players.")
            public boolean duplicateLoginScanFastPath = true;
            @Comment("Skips empty boss bar broadcasts and uses indexed snapshots for boss bar visibility updates.")
            public boolean bossEventBroadcastFastPath = true;
            @Comment("Caches Bukkit visibility wrappers during join player-info fanout.")
            public boolean joinPlayerInfoFastPath = true;
            @Comment("Avoids stream allocation and generic collection writers while building player-info update packets.")
            public boolean playerInfoPacketFastPath = true;
            @Comment("Uses indexed loops for global level events instead of lambda iteration.")
            public boolean globalLevelEventFastPath = true;
            @Comment("Caches repeated values while broadcasting block break progress to nearby players.")
            public boolean blockBreakProgressFastPath = true;
            @Comment("Uses indexed loops for common PlayerList lookup APIs.")
            public boolean playerLookupFastPath = true;
            @Comment("Uses indexed loops for scoreboard packet fanout while preserving per-player Bukkit scoreboard checks.")
            public boolean scoreboardBroadcastFastPath = true;
            @Comment("Caches online team players before remaking scoreboard waypoint connections across worlds.")
            public boolean scoreboardWaypointFastPath = true;
            @Comment("Combines sleep status deep-sleeper checks into a single indexed player pass.")
            public boolean sleepStatusFastPath = true;
            @Comment("Uses indexed loops when updating entity tracking visibility against level player lists.")
            public boolean entityTrackerUpdatePlayersFastPath = true;
            @Comment("Iterates the entity tracker primitive map directly during player join, quit, and tracked-entity scans.")
            public boolean entityMapIterationFastPath = true;
            @Comment("Uses indexed loops for particle command target fanout.")
            public boolean particleCommandFastPath = true;
            @Comment("Avoids per-command played-player list allocation in the playsound command.")
            public boolean playSoundCommandFastPath = true;
            @Comment("Avoids stream allocation while applying Bukkit visibility filters in the list command.")
            public boolean listPlayersCommandFastPath = true;
            @Comment("Uses indexed loops for stopsound command target fanout.")
            public boolean stopSoundCommandFastPath = true;
            @Comment("Builds team message receiver lists with indexed player scans instead of streams.")
            public boolean teamMsgCommandFastPath = true;
            @Comment("Builds JSON-RPC online player DTO lists with indexed loops instead of streams.")
            public boolean jsonRpcPlayerListFastPath = true;
            @Comment("Uses indexed loops for JSON-RPC targeted system message delivery.")
            public boolean jsonRpcTargetedMessageFastPath = true;
            @Comment("Uses indexed loops for JSON-RPC system message broadcasts.")
            public boolean jsonRpcBroadcastMessageFastPath = true;
            @Comment("Uses indexed loops for title command target fanout.")
            public boolean titleCommandFastPath = true;
            @Comment("Uses indexed loops for transfer command target fanout.")
            public boolean transferCommandFastPath = true;
            @Comment("Uses indexed loops for dialog command target fanout.")
            public boolean dialogCommandFastPath = true;
            @Comment("Uses indexed loops for experience command target updates.")
            public boolean experienceCommandFastPath = true;
            @Comment("Uses indexed loops for recipe command target updates.")
            public boolean recipeCommandFastPath = true;
            @Comment("Avoids stream allocation when resolving recipe keys for a player's recipe book updates.")
            public boolean recipeKeyAwardFastPath = true;
            @Comment("Avoids stream pipelines while listing and saving server user-list entries.")
            public boolean userListStorageFastPath = true;
            @Comment("Uses indexed loops for gamemode command target updates.")
            public boolean gameModeCommandFastPath = true;
            @Comment("Uses indexed loops for give command target item fanout.")
            public boolean giveCommandFastPath = true;
            @Comment("Uses indexed loops for clear command target inventory scans.")
            public boolean clearCommandFastPath = true;
            @Comment("Uses indexed loops for spawnpoint command target updates.")
            public boolean setSpawnCommandFastPath = true;
            @Comment("Uses indexed loops for effect command target updates.")
            public boolean effectCommandFastPath = true;
            @Comment("Uses indexed loops for teleport command target updates.")
            public boolean teleportCommandFastPath = true;
            @Comment("Uses indexed loops for tellraw command target fanout.")
            public boolean tellRawCommandFastPath = true;
            @Comment("Uses indexed loops for msg, tell, and w command target fanout.")
            public boolean msgCommandFastPath = true;
            @Comment("Uses indexed loops for kick command target fanout.")
            public boolean kickCommandFastPath = true;
            @Comment("Uses indexed loops for kill command target fanout.")
            public boolean killCommandFastPath = true;
            @Comment("Uses indexed loops for swing command target fanout.")
            public boolean swingCommandFastPath = true;
            @Comment("Uses indexed loops for tag command target scans and updates.")
            public boolean tagCommandFastPath = true;
            @Comment("Uses indexed loops for serverpack command connection fanout.")
            public boolean serverPackCommandFastPath = true;
            @Comment("Uses indexed loops for debugconfig command connection scans.")
            public boolean debugConfigCommandFastPath = true;
            @Comment("Uses indexed loops for ban-ip command disconnect fanout.")
            public boolean banIpCommandFastPath = true;
            @Comment("Uses indexed loops for scoreboard players command target updates.")
            public boolean scoreboardCommandFastPath = true;
            @Comment("Uses indexed loops for team command member updates.")
            public boolean teamCommandFastPath = true;
            @Comment("Avoids stream and lambda allocation while listing or saving large scoreboards.")
            public boolean scoreboardStorageFastPath = true;
            @Comment("Pre-sizes and indexes execute command source fanout lists when entity selections expose their size.")
            public boolean executeCommandFastPath = true;
            @Comment("Uses indexed loops and avoids stream allocation in the function command when function selections are list-backed.")
            public boolean functionCommandFastPath = true;
            @Comment("Uses indexed loops for advancement command player and advancement fanout when selections are list-backed.")
            public boolean advancementCommandFastPath = true;
            @Comment("Avoids stream allocation while loading saved player advancement progress.")
            public boolean advancementStorageFastPath = true;
            @Comment("Avoids temporary listener list allocation in common single-listener advancement trigger checks.")
            public boolean advancementTriggerFastPath = true;
            @Comment("Uses indexed loops for common nearby and nearest player lookups.")
            public boolean nearbyPlayerLookupFastPath = true;
            @Comment("Uses indexed loops for natural spawning player and chunk passes during chunk ticks.")
            public boolean chunkSpawnTickLoopFastPath = true;
            @Comment("Avoids empty list allocation and uses indexed loops for local mob-cap player accounting.")
            public boolean localMobCapPlayerFastPath = true;
            @Comment("Uses an indexed server connection tick loop to reduce iterator overhead at high connection counts.")
            public boolean networkConnectionTickFastPath = true;
            @Comment("Drains ready queued network actions with a poll loop instead of iterator removal during connection ticks.")
            public boolean networkQueueDrainFastPath = true;
            @Comment("Batches high-fanout player broadcasts as write-then-flush passes while preserving normal packet send hooks.")
            public boolean networkBroadcastFlushFastPath = true;
            @Comment("Writes common network packet collections with explicit loops instead of generic lambda collection encoders.")
            public boolean networkCollectionPacketFastPath = true;
            @Comment("Avoids stream and generic collection writers while building and encoding command tree packets.")
            public boolean commandTreePacketFastPath = true;
            @Comment("Avoids stream and forEach dispatch in common command tab-completion suggestion providers.")
            public boolean commandSuggestionProviderFastPath = true;
            @Comment("Avoids stream pipelines while converting chat acknowledgement and command suggestion packet lists.")
            public boolean commandSuggestionPacketFastPath = true;
            @Comment("Avoids stream pipelines in common server game packet handling paths such as book edits and interaction resyncs.")
            public boolean serverGamePacketFastPath = true;
            @Comment("Avoids stream collectors and generic map writers while building and encoding tag sync packets.")
            public boolean tagNetworkPacketFastPath = true;
            @Comment("Writes advancement sync packets with direct loops instead of nested generic map and collection encoders.")
            public boolean advancementPacketFastPath = true;
            @Comment("Avoids stream allocation when selecting pending chunks to send to moving players.")
            public boolean chunkSendQueueFastPath = true;
            @Comment("Skips the player chunk-sender send pass while it is fully idle or waiting for client acknowledgements.")
            public boolean playerChunkSenderIdleFastPath = true;
            @Comment("Avoids stream pipelines while loading and saving chunk entity, post-processing, and structure reference data.")
            public boolean chunkStorageSerializationFastPath = true;
            @Comment("Avoids lambda/removeIf overhead while tracking pending chunk-load batches.")
            public boolean chunkLoadCounterFastPath = true;
            @Comment("Avoids temporary chunk-position lists while scanning square chunk ranges.")
            public boolean chunkRangeScanFastPath = true;
            @Comment("Caches shared packets and uses explicit player loops for dense area entity effects.")
            public boolean densePlayerEffectFanoutFastPath = true;
            @Comment("Avoids stream pipelines while packing and unpacking world saved-data structures such as POI and raids.")
            public boolean worldDataSerializationFastPath = true;
            @Comment("Avoids stream allocation and pre-sizes block entity data while building full chunk packets.")
            public boolean levelChunkPacketDataFastPath = true;
            @Comment("Pre-sizes light update data lists and uses indexed writes for chunk light packets.")
            public boolean lightUpdatePacketDataFastPath = true;
            @Comment("Uses indexed section block update scans and skips block-position creation for non-block-entity states.")
            public boolean sectionBlockUpdateFastPath = true;
            @Comment("Skips passenger-sync filtering when no player passengers are involved and uses indexed membership checks otherwise.")
            public boolean passengerSyncFastPath = true;
            @Comment("Avoids stream allocation when removing passengers from multi-passenger entities.")
            public boolean entityPassengerListFastPath = true;
            @Comment("Avoids stream tree allocation while teleporting nested passenger stacks.")
            public boolean passengerTreeIterationFastPath = true;
            @Comment("Uses explicit iterator and indexed passenger loops in the main entity tick path.")
            public boolean entityTickLoopFastPath = true;
            @Comment("Skips entity tick-list iteration setup when a world has no tickable entities.")
            public boolean entityTickListEmptyFastPath = true;
            @Comment("Reuses empty collision result lists and avoids allocation in common no-entity collision checks.")
            public boolean entityCollisionListFastPath = true;
            @Comment("Bounds the entity push scan to the per-entity collision cap (max-entity-collisions) when the entity cramming gamerule is disabled, so a dense crowd costs O(max-entity-collisions) per entity instead of scanning every nearby entity. Pushed entities are identical to the capped loop. Has no effect while the entity cramming gamerule is above zero.")
            public boolean denseCrowdPushScanFastPath = true;
            @Comment("Stops the entity cramming damage count once it crosses the cramming threshold instead of counting every entity in a dense crowd. The cramming damage decision is unchanged.")
            public boolean crammingCountFastPath = true;
            @Comment("Defers allocating the per-player experience-orb pickup list until an orb is actually in range, avoiding a throwaway list allocation every tick for every player. Pickup behavior is unchanged.")
            public boolean playerPickupOrbAllocFastPath = true;
            @Comment("Experimental, opt-in. Encodes an entity movement packet once when it is broadcast to many tracking players and reuses the bytes for the remaining recipients, instead of re-encoding per connection (helps crowded areas with hundreds of viewers). Only locale-independent movement packets are cached, so the wire output is identical; compression and encryption still run per connection. Disabled by default until validated under real multi-player load.")
            public boolean packetEncodingCacheFastPath = false;
            @Comment("Experimental, opt-in. In the entity tracker, a stationary entity only re-evaluates tracking for players that moved this tick (instead of every nearby player every tick), with a staggered full refresh every 32 ticks. Turns the O(players^2) per-tick tracker cost into O(movers x players) for idle crowds - the dominant cost for thousands of players standing in a tiny area. Disabled by default until validated with a real client (visibility/vanish edge cases self-correct within 32 ticks).")
            public boolean trackerIdleSkipFastPath = false;
            @Comment("Full-refresh interval (ticks) for trackerIdleSkipFastPath: every N ticks a stationary entity re-evaluates all nearby players (staggered per entity) to catch rare non-positional tracking changes. Higher = cheaper for very dense crowds but slower to reflect those rare changes. 1 disables the idle skip. Default 256.")
            public int trackerIdleSkipRefreshTicks = 256;
            @Comment("Opt-in for extreme density. Re-evaluates WHICH players can see each entity only every N ticks (staggered per entity) while still broadcasting movement every tick, cutting the O(movers x viewers) tracking re-eval cost when thousands move in a tiny area. The trade-off is that entities entering/leaving view-distance range appear/disappear up to N ticks late (imperceptible in a packed area; vanish/hide stays immediate). 1 = off (default, preserves vanilla feel); high-pop servers can set 3-4.")
            public int trackerUpdateThrottleTicks = 1;
            @Comment("Skips the per-tick natural-spawn census (which iterates every entity to compute mob caps) and the per-player mob-count bookkeeping when the doMobSpawning gamerule is disabled. The census only feeds the spawn loop, which is already off, so this is pure saved work for servers that disable mob spawning. Spawning behavior is unchanged.")
            public boolean spawnCensusSkipFastPath = true;
            @Comment("Experimental, opt-in. Computes entity activation range by iterating activatable entities once (early-exit on the first in-range player) instead of scanning all entities around every player. Always-active entities (players) resolve in O(1), turning the O(players^2) activation scan into roughly O(entities) for dense crowds. Disabled by default until validated with live mobs.")
            public boolean activationRangeIterateOnceFastPath = false;
            @Comment("Uses direct backing-list scans for entity section range queries and class instance maps.")
            public boolean entitySectionScanFastPath = true;
            @Comment("Uses direct tracker loops and avoids redundant section scans during entity-fluid interaction updates.")
            public boolean entityFluidInteractionFastPath = true;
            @Comment("Avoids temporary equipment-slot lists while selecting fall-flying glider damage targets.")
            public boolean fallFlyingEquipmentFastPath = true;
            @Comment("Avoids removeIf and stream scans while serializing entity equipment and drop-chance data.")
            public boolean entityEquipmentSerializationFastPath = true;
            @Comment("Avoids stream/removeIf overhead while applying area-effect cloud effects to dense entity groups.")
            public boolean areaEffectCloudFastPath = true;
            @Comment("Avoids stream and temporary list allocation when waking all sleeping players.")
            public boolean sleepWakeupLoopFastPath = true;
            @Comment("Avoids per-tick block entity removal set allocation when no block entity tickers are removed.")
            public boolean blockEntityTickRemovalFastPath = true;
            @Comment("Uses indexed loops for per-world custom spawner ticking.")
            public boolean customSpawnerLoopFastPath = true;
            @Comment("Caches autosave limits and uses indexed loops in player save passes.")
            public boolean playerSaveLoopFastPath = true;
            @Comment("Avoids constant-message lambda fanout for common system message broadcasts.")
            public boolean systemMessageBroadcastFastPath = true;
            @Comment("Uses indexed loops and cached per-message state in player chat broadcasts.")
            public boolean chatBroadcastFastPath = true;
            @Comment("Caches disconnect messages and uses indexed loops when disconnecting all players.")
            public boolean playerDisconnectFanoutFastPath = true;
            @Comment("Uses indexed loops and cached names while cleaning map data for removed players.")
            public boolean playerMapCleanupFastPath = true;
            @Comment("Uses indexed loops for Bukkit onEntityRemove callbacks to all online players.")
            public boolean entityRemoveCallbackFastPath = true;
            @Comment("Avoids stream pipelines while converting entity event participant and item-result lists.")
            public boolean entityEventListFastPath = true;
            @Comment("Avoids stream collectors while exposing raid raider collections to CraftBukkit.")
            public boolean raidRaiderSetFastPath = true;
            @Comment("Caches explosion fanout state while sending explosion packets to nearby players.")
            public boolean explosionPacketFanoutFastPath = true;
            @Comment("Caches visibility state and receiver counts while sending particle packets.")
            public boolean particleFanoutFastPath = true;
            @Comment("Selects random alive players without allocating a temporary filtered player list.")
            public boolean randomPlayerFastPath = true;
            @Comment("Caches block event broadcast state while dispatching queued world block events.")
            public boolean blockEventBroadcastFastPath = true;
            @Comment("Avoids stream allocation when notifying nearby neutral mobs that a player died.")
            public boolean neutralMobDeathFastPath = true;
            @Comment("Uses indexed player scans when broadcasting game rule changes to operators.")
            public boolean gameRuleOperatorBroadcastFastPath = true;
            @Comment("Avoids lambda fanout and pre-sizes player chunk lists while resending chunk biome packets.")
            public boolean chunkBiomeBroadcastFastPath = true;
            @Comment("Avoids stream allocation and generic collection writers while building chunk biome packets.")
            public boolean chunkBiomePacketFastPath = true;
            @Comment("Avoids primitive forEach lambda dispatch while exposing unique paletted chunk container entries.")
            public boolean chunkPaletteIterationFastPath = true;
            @Comment("Uses explicit iterator writes when flushing dirty section-storage chunks.")
            public boolean sectionStorageFlushFastPath = true;
            @Comment("Avoids stream allocation while collecting pending chunk IO futures for synchronization.")
            public boolean chunkIoSynchronizeFastPath = true;
            @Comment("Uses explicit loops for chunk block-entity removal, rebinding, and post-load registration.")
            public boolean chunkBlockEntityLifecycleFastPath = true;
            @Comment("Avoids unnecessary packet and equipment list growth while pairing tracked entities to players.")
            public boolean entityPairingPacketFastPath = true;
            @Comment("Avoids repeated predicate checks and receiver scans while batching entity tracker packet fanout.")
            public boolean entityTrackerFanoutFastPath = true;
            @Comment("Avoids stream allocation and pre-sizes lists while collecting and sending entity attributes.")
            public boolean entityAttributePacketFastPath = true;
            @Comment("Avoids lambda fanout while applying, removing, and copying entity attribute modifiers.")
            public boolean entityAttributeMutationFastPath = true;
            @Comment("Pre-sizes dirty entity metadata lists and uses indexed packet writes for entity data updates.")
            public boolean entityMetadataPacketFastPath = true;
            @Comment("Avoids stream allocation while unloading a quitting player's single-player vehicle stack.")
            public boolean playerVehicleUnloadFastPath = true;
            @Comment("Avoids lambda iteration while restoring saved per-player transient entity state.")
            public boolean playerStateRestoreFastPath = true;
            @Comment("Skips full inventory special-item scans on player ticks until the player can actually carry map-tracked items.")
            public boolean playerInventorySpecialItemFastPath = true;
            @Comment("Avoids reapplying unchanged per-player attribute state every tick while periodically refreshing for plugin changes.")
            public boolean playerAttributeStateFastPath = true;
            @Comment("Batches respawn, dimension-change, and full player-info packet bursts through the connection no-flush path.")
            public boolean playerTransitionPacketBatchFastPath = true;
            @Comment("Avoids container menu slot/data scans when no listeners or remote synchronizer can observe changes.")
            public boolean playerContainerBroadcastFastPath = true;
            @Comment("Avoids lambda allocation while building game rule value response packets.")
            public boolean gameRuleValueRequestFastPath = true;
            @Comment("Uses direct entity-section loops for chunk entity load, unload, save, and visibility transitions.")
            public boolean entitySectionManagerFastPath = true;
            @Comment("Avoids stream allocation in player-aware AI sensors while preserving nearest-player ordering.")
            public boolean playerAiSensorFastPath = true;
            @Comment("Avoids stream allocation in mob AI detection sensors and warden warning propagation.")
            public boolean mobAiDetectionFastPath = true;
            @Comment("Avoids stream collectors and temporary lists in animal and village AI memory scans.")
            public boolean animalAiMemoryFastPath = true;
            @Comment("Avoids stream allocation while selecting and assigning schooling fish leaders.")
            public boolean schoolingFishLeaderFastPath = true;
            @Comment("Avoids stream and lambda fanout while copying and counting villager gossip data.")
            public boolean villagerGossipFastPath = true;
            @Comment("Avoids stream allocation while scanning villager inventories and golem-spawn agreement groups.")
            public boolean villagerAiScanFastPath = true;
            @Comment("Avoids stream allocation while checking gate behavior running state in AI brain ticks.")
            public boolean aiGateBehaviorFastPath = true;
            @Comment("Avoids lambda fanout while ticking, clearing, and serializing AI brain memories.")
            public boolean aiBrainMemoryFastPath = true;
            @Comment("Avoids iterator lambdas while updating mob goal selector state.")
            public boolean aiGoalSelectorFastPath = true;
            @Comment("Avoids lambda dispatch while shuffling weighted AI behavior lists.")
            public boolean aiShufflingListFastPath = true;
            @Comment("Avoids stream sorting while selecting ram attack start positions.")
            public boolean aiRamTargetFastPath = true;
            @Comment("Avoids stream collectors while building small random-walk and long-jump AI candidate lists.")
            public boolean aiCandidateBuildFastPath = true;
            @Comment("Avoids stream and cast-list allocation while collecting leashable entities in nearby areas.")
            public boolean leashAreaScanFastPath = true;
            @Comment("Avoids world border listener snapshot allocation when there are zero or one listeners.")
            public boolean worldBorderListenerFastPath = true;
            @Comment("Uses indexed player fanout loops for server-level difficulty, gamerule, waypoint, time, and network flush updates.")
            public boolean minecraftServerPlayerFanoutFastPath = true;
            @Comment("Uses indexed player scans when sending low disk space warnings to online administrators.")
            public boolean lowDiskAdminWarningFastPath = true;
            @Comment("Uses indexed loops for server notification service fanout.")
            public boolean notificationServiceFanoutFastPath = true;
            @Comment("Tracks dirty world saved-data entries directly so autosaves avoid scanning every cached saved-data object in every world.")
            public boolean savedDataDirtyIndexFastPath = true;
            @Comment("Hoists repeated global listener checks out of the per-world tick loop for large multi-world servers.")
            public boolean serverWorldTickLoopFastPath = true;
            @Comment("Skips per-world time packet construction for empty worlds during periodic time synchronization.")
            public boolean sparseWorldTimeSyncFastPath = true;
            @Comment("Caches each ServerLevel world border instead of resolving it through SavedDataStorage on hot collision and interaction paths.")
            public boolean serverLevelWorldBorderCacheFastPath = true;
            @Comment("Avoids status sample allocation and shuffle work when the ping player sample is empty, hidden, or disabled.")
            public boolean serverStatusSampleFastPath = true;
            @Comment("Uses allocation-light queue drains and indexed residual server tick loops for tickables and chunk send flushing.")
            public boolean serverResidualTickLoopFastPath = true;
            @Comment("Uses UUID-indexed diffing for custom bossbar player target updates instead of nested collection scans.")
            public boolean customBossEventPlayerSetFastPath = true;
            @Comment("Polls chunk task queues across many worlds in a round-robin budget instead of scanning every world on every idle task poll.")
            public boolean chunkTaskPollRoundRobinFastPath = true;
            @Comment("Maximum number of worlds checked per regular chunk task poll when round-robin polling is enabled.")
            public int chunkTaskPollRoundRobinBudget = 8;
            @Comment("Forces a full chunk task poll sweep every N regular polls to keep low-activity worlds responsive.")
            public int chunkTaskPollFullSweepInterval = 64;
            @Comment("Uses a cached ServerLevel array snapshot for hot multi-world server loops such as clocks, ticking, autosave, and time sync.")
            public boolean serverLevelSnapshotIterationFastPath = true;
            @Comment("Caches whether the global player list can use one shared latency packet until Bukkit visibility state changes.")
            public boolean latencyVisibilityStateCacheFastPath = true;
            @Comment("Rotates incremental player autosave scans through the online player list instead of starting at index zero every autosave tick.")
            public boolean playerAutosaveRoundRobinFastPath = true;
            @Comment("Skips the periodic every-60-tick entity position packet when the entity has not moved since its last sent position. That packet carries a zero movement delta the client applies as nothing (on-ground changes already force a full teleport), so sending it to every viewer is pure waste - a large share of broadcast traffic in entity-dense areas. The forced-teleport interval still fires a full position resync, so there is no drift, and output to vanilla clients is unchanged.")
            public boolean skipIdlePositionSyncFastPath = true;
            // AGC start - hit rewind and adaptive view distance config
            @Comment("Enables server-authoritative hit rewind for PvP: rewinds the target player's position by the attacker's RTT/2 ticks when validating melee hits. Reduces the PvP disadvantage for high-ping players without changing damage values. Enabled by default in AGC aggressive-compatible mode with a conservative 6-tick cap; set false for strict vanilla parity.")
            public boolean hitRewindEnabled = true;
            @Comment("Maximum ticks to rewind target position for hit rewind (hitRewindEnabled). 1 tick = 50ms. Default 6 = 300ms max compensation. Higher values help more extreme pings but allow more server-side positional divergence.")
            public int hitRewindMaxTicks = 6;
            @Comment("Scales entity tracking range for high-ping players to reduce their packet load without affecting chunk loading. RTT>150ms=90%, RTT>300ms=80%, RTT>500ms=60%. Chunk view distance is unaffected.")
            public boolean adaptiveViewDistanceFastPath = true;
            // AGC end - hit rewind and adaptive view distance config
            @Comment("Aggressive-compatible default. MULTITHREADING: ticks independent worlds in parallel across a pool of tick-threads (plus the main thread) with a barrier at the end of the world phase, instead of one world after another on a single thread. Each world stays internally single-threaded, so its chunk system and the plugin events fired during its tick run on exactly one thread - chunk sync stays correct and single-world plugins behave as on vanilla Paper. Cross-world entity transfers (portals) during the parallel phase are deferred to the main thread post-barrier so they never race a world being ticked. Best for servers that spread players across many active worlds (minigame/arena/survival instances): per-world tick cost then scales with CPU cores instead of summing on one core. CAVEAT: a plugin that synchronously reads/writes a DIFFERENT world from inside an event handler is not safe under this mode. Enabled by default in AGC aggressive-compatible mode, but the compatibility bridge keeps Paper main-thread semantics when plugins are unknown or risky.")
            public boolean parallelWorldTick = true;
            @Comment("Worker tick-threads for parallelWorldTick. 0 = auto (available processors - 1). The main thread also ticks one world, so total parallelism is this + 1. Capped at the number of worlds each tick.")
            public int parallelWorldTickThreads = 0;
            @Comment("Only engage parallelWorldTick when at least this many worlds exist. Below this the sequential path is used (parallel dispatch overhead is not worth it for a couple of worlds). Default 4 in aggressive-compatible mode.")
            public int parallelWorldTickMinWorlds = 4;
            @Comment("Unsafe override for parallelWorldTick compatibility scan. Keep false for production. When true AGC may tick worlds in parallel even when plugins are unknown or flagged as cross-world/event sensitive.")
            public boolean parallelWorldTickForceUnsafe = false;
            @Comment("Keeps Bukkit/Paper plugin-facing callbacks on the primary thread by translating worker-discovered plugin-sensitive work to a post-barrier main-thread queue. Enabled by default; experimental features should not bypass it.")
            public boolean pluginThreadTranslationLayer = true;
            @Comment("Maximum translated plugin-sensitive tasks drained on the primary thread per tick. Prevents worker-discovered compatibility work from monopolising the main thread.")
            public int pluginThreadTranslationMaxDrainPerTick = 4096;
            @Comment("Enables packet-priority budgeting hooks. Movement/combat packets are preserved first, chunk/light next, cosmetic/low-value metadata last. Enabled by default; AGC never drops packets and uses a one-tick deadline batcher so low-value flush coalescing cannot become visible gameplay latency.")
            public boolean packetPriorityBudgeting = true;
            @Comment("Per-player soft packet budget in bytes per second when packetPriorityBudgeting is enabled. AGC does not drop packets; it delays low-priority flushes under pressure.")
            public int packetBudgetBytesPerSecond = 512 * 1024;
            @Comment("Per-player packet budget burst in bytes when packetPriorityBudgeting is enabled.")
            public int packetBudgetBurstBytes = 1024 * 1024;
            @Comment("Enables bounded chunk send/load/generation admission hooks for high-player servers. Enabled by default; AGC preserves queue order and only delays starting new load/generate work under pressure.")
            public boolean chunkQueueBudgeting = true;
            @Comment("Per-player chunk packet flush budget per second when chunkQueueBudgeting is enabled. Exceeding the budget writes without an immediate flush instead of dropping chunks.")
            public int chunkBudgetSendsPerSecond = 128;
            @Comment("Reserved per-player chunk load budget per second for future queue integration.")
            public int chunkBudgetLoadsPerSecond = 96;
            @Comment("Reserved per-player chunk generation budget per second for future queue integration.")
            public int chunkBudgetGeneratesPerSecond = 32;
            @Comment("Allows empty worlds to skip avoidable packet/debug fanout while still keeping chunk ticks, block entities, redstone, scheduled tasks and plugin semantics intact.")
            public boolean emptyWorldPacketHibernation = true;
            @Comment("Enables automatic rollback governor for AGC experimental flags when MSPT, plugin exceptions or queue backlog spike.")
            public boolean automaticRollbackGovernor = true;
            @Comment("MSPT threshold that causes the AGC rollback governor to throttle or disable experimental features until restart.")
            public double rollbackMsptThreshold = 80.0D;
            @Comment("Queue depth threshold that causes the AGC rollback governor to throttle packet/chunk/thread-translation experimental features.")
            public int rollbackQueueDepthThreshold = 50000;
            @Comment("Enables AGC adaptive runtime scaling profiles. Enabled by default. AGC retunes budgets while preserving packet deadlines, chunk queue order and plugin callback order.")
            public boolean adaptiveScalingProfiles = true;
            @Comment("Online player count at which AGC may enter its high-density budget profile when adaptiveScalingProfiles is enabled.")
            public int adaptiveScalingHighDensityPlayers = 96;
            @Comment("MSPT at or above this value enters AGC emergency profile when adaptiveScalingProfiles is enabled. Emergency profile tightens budgets but still keeps packet deadlines and ordered commits.")
            public double adaptiveScalingEmergencyMspt = 100.0D;
            @Comment("Central AGC optimisation envelope. When enabled, every aggressive optimisation must pass the same vanilla-feel, plugin-compatibility, queue-pressure and ordered-commit checks before changing scheduling/batching behaviour.")
            public boolean optimizationEnvelopeEnabled = true;
            @Comment("Keeps plugin-facing semantics as the top priority. Unknown or cross-world/event-sensitive plugins use deterministic main-thread commits while read-only preparation may still be optimised.")
            public boolean optimizationPreservePluginCompatibility = true;
            @Comment("Keeps interactive gameplay feel as the top priority. AGC may batch low-value work only inside the configured deadline so emergency pressure does not add visible latency.")
            public boolean optimizationPreserveVanillaFeel = true;
            @Comment("Maximum interactive delay AGC is allowed to add for low-priority batching. Default 1 means packets/chunk flushes must be drained by the end of the next server tick.")
            public int optimizationMaxInteractiveDelayTicks = 1;
            @Comment("When true, unknown or warning-level plugins keep world mutations and Bukkit callbacks in deterministic main-thread commit order unless the unsafe override is explicitly enabled.")
            public boolean optimizationStrictUnknownPluginParallelGate = true;
            @Comment("Enables the alpha8 semantic invariant ledger. Optimisations record whether they used read-only parallel prepare, ordered commit, deadline batching, or conflict serialisation.")
            public boolean semanticInvariantLedger = true;
            @Comment("Enables the deterministic task pipeline for read-only worker preparation with primary-thread ordered commits.")
            public boolean deterministicTaskPipeline = true;
            @Comment("Maximum prepared AGC commits drained per server tick by the deterministic task pipeline.")
            public int deterministicTaskPipelineMaxCommitsPerTick = 8192;
            @Comment("Requires Bukkit/plugin callbacks discovered by AGC world planning to commit on the primary thread. Keep true for plugin compatibility.")
            public boolean worldPlannerPrimaryThreadPluginCallbacks = true;
            @Comment("Per-player budget for allocation/read-only entity tracker fast paths. This never skips entity ticks or plugin events; it only decides when to use AGC tracker helper paths.")
            public int entityTrackerFastPathBudgetPerTick = 4096;
            @Comment("Maximum connections AGC may end-of-tick flush after low-priority packet flush coalescing. Higher values help very large servers; low values preserve latency headroom.")
            public int networkDeferredFlushDrainPerTick = 16384;
            @Comment("Maximum connections allowed to wait for AGC's end-of-tick deferred flush. Once full, new low-priority batches preserve immediate order rather than extending the deadline.")
            public int networkDeferredFlushMaxPending = 32768;
            @Comment("Enables AGC alpha9 no-invasion optimiser. Aggressive features must use read-only prepare, FIFO fair queue, deadline-ordered batching, conflict-free world waves, or ordered commit lanes; gameplay state is never skipped or reordered.")
            public boolean noInvasionOptimizer = true;
            @Comment("Records no-invasion decisions into the semantic ledger so operators can prove optimisations are not changing plugin-visible order.")
            public boolean noInvasionStrictSemantics = true;
            @Comment("Maximum pending translated plugin tasks before world-wave execution uses ordered commit lanes only. This does not disable optimisation; it prevents callback reordering.")
            public int noInvasionMaxTranslatorPending = 8192;
            @Comment("Maximum token carry multiplier for the AGC per-player chunk FIFO fair queue. Higher values let returning players catch up without changing per-player chunk order.")
            public int chunkFairQueueMaxCarryMultiplier = 4;
            @Comment("Enables AGC alpha10 per-player intent scheduler. It gives every player deterministic FIFO tickets for cosmetic network, chunk, entity-snapshot and arena work so aggressive optimisation cannot reorder their visible stream.")
            public boolean playerIntentScheduler = true;
            @Comment("Per-player cosmetic-network intent budget per tick. Interactive packets are never charged to this budget.")
            public int playerIntentNetworkCosmeticBudget = 256;
            @Comment("Per-player chunk intent budget per tick across send/load/generate starts. Queue heads are not skipped when this budget is exhausted.")
            public int playerIntentChunkBudget = 96;
            @Comment("Per-player arena-flow intent budget per tick for mini-game helper bookkeeping and visibility work.")
            public int playerIntentArenaBudget = 512;
            @Comment("Enables AGC alpha10 deterministic shard planner for large multi-world and mini-game networks. Shards are planning labels, not semantic thread ownership changes.")
            public boolean shardPlanner = true;
            @Comment("Target player count per AGC planning shard. Smaller values increase parallel read-only preparation granularity without changing world tick order.")
            public int shardPlayersPerShard = 64;
            @Comment("Maximum AGC planning shards to track.")
            public int shardMaxShards = 4096;
            @Comment("Enables plugin-declared compatibility contracts. Contracts guide no-invasion lanes but never permit off-thread Bukkit mutations.")
            public boolean pluginCompatibilityContracts = true;
            @Comment("Enables AGC alpha11 central tick budget arbiter. The arbiter assigns aggressive prepare/batch budgets across network, chunks, entities, worlds, players and arenas without changing visible commit order.")
            public boolean tickBudgetArbiter = true;
            @Comment("Target MSPT for AGC aggressive budget shaping. Below this value AGC spends more read-only/batching budget; above it AGC tightens non-visible prepare work without skipping gameplay state.")
            public double performanceTargetMspt = 42.0D;
            @Comment("Hard MSPT guard for AGC performance standard. Reaching this pressure tightens read-only/batch budgets but keeps plugin-visible ordering intact.")
            public double performanceHardMspt = 95.0D;
            @Comment("Total abstract AGC budget units refilled each tick for non-visible prepare/batch lanes. Higher values are aggressive for large servers but still cannot reorder visible state.")
            public long tickBudgetUnitsPerTick = 500000L;
            @Comment("Minimum budget units each AGC category receives per tick so chunk/entity/network/minigame work cannot starve completely.")
            public long tickBudgetMinUnitsPerCategory = 512L;
            @Comment("Divisor applied to the central tick budget under hard pressure. Larger values tighten aggressive prepare work more strongly while ordered commits still run.")
            public int tickBudgetHardPressureDivisor = 3;
            @Comment("Maximum semantic queue depth tolerated by the AGC performance standard before it tightens prepare/batch lanes.")
            public long performanceMaxSemanticQueueDepth = 131072L;
            @Comment("Enables latency SLO accounting for one-tick network batching and ordered commit drains.")
            public boolean latencySloAccounting = true;
            @Comment("Maximum ordered commit age in ticks for AGC helper pipelines. Bukkit-visible mutations stay ordered regardless of this value.")
            public int latencySloMaxOrderedCommitTicks = 1;
            @Comment("Enables per-tick read-only entity spatial snapshot cache for dense arenas. This never owns live entity state and expires on tick boundaries.")
            public boolean entitySpatialSnapshotCache = true;
            @Comment("Maximum read-only entity spatial snapshot cache entries retained for the current tick.")
            public int entitySpatialSnapshotMaxEntries = 262144;
            @Comment("Enables mini-game burst planner for arena start/reset/prewarm waves. It admits read-only prewarm and ordered commits without reordering Bukkit-visible operations.")
            public boolean minigameBurstPlanner = true;
            @Comment("Per-arena read-only prewarm budget per tick for AGC mini-game burst planner.")
            public int minigameArenaPrewarmBudgetPerTick = 4096;
            @Comment("Per-arena ordered commit ticket budget per tick for AGC mini-game burst planner.")
            public int minigameArenaCommitBudgetPerTick = 2048;
            @Comment("Enables AGC alpha12 survival scale coordinator. It targets thousands of ordinary survival players by sharing read-only work and preserving all Bukkit-visible order.")
            public boolean survivalScaleCoordinator = true;
            @Comment("AGC survival scale target player count for high-density budget shaping. This is a goal, not a promise; semantics remain unchanged under every mode.")
            public int survivalScaleTargetPlayers = 3000;
            @Comment("AGC survival scale target TPS. 20.0 keeps the standard focused on vanilla-feel 50 ms ticks.")
            public double survivalScaleTargetTps = 20.0D;
            @Comment("Players per interest-graph cell that AGC treats as a survival hotspot for shared read-only fanout preparation.")
            public int survivalScaleHotspotPlayersPerCell = 48;
            @Comment("Enables AGC alpha12 resource efficiency engine for CPU/memory/network-aware invisible work budgeting.")
            public boolean resourceEfficiencyEngine = true;
            @Comment("CPU percentage AGC keeps reserved for ordered commits, plugins, GC, OS and Netty so aggressive prepare work does not steal visible latency headroom.")
            public int resourceEfficiencyReserveCpuPercent = 18;
            @Comment("Free-heap threshold in bytes where AGC tightens read-only/cache budgets before GC pressure becomes visible.")
            public long resourceEfficiencyMaxMemoryPressureBytes = 1073741824L;
            @Comment("Abstract resource units refilled per usable CPU core for AGC invisible prepare lanes.")
            public long resourceEfficiencyBaseUnitsPerCore = 180000L;
            @Comment("Maximum carry multiplier for resource efficiency buckets. Carry improves burst handling without letting old work monopolize the server.")
            public long resourceEfficiencyMaxCarryMultiplier = 2L;
            @Comment("Enables tick-local read-only interest graph for shared packet/entity/chunk fanout preparation in dense survival hotspots.")
            public boolean interestGraph = true;
            @Comment("Coarse interest graph cell size in blocks. Larger values increase sharing, smaller values improve locality precision.")
            public int interestGraphCellSizeBlocks = 32;
            @Comment("Players per interest graph cell before AGC starts sharing duplicate read-only fanout plans.")
            public int interestGraphTargetPlayersPerCell = 24;
            @Comment("Maximum interest graph cells retained in one tick.")
            public int interestGraphMaxCells = 262144;
            @Comment("Enables global fairness matrix across players, worlds, arenas and regions so hotspots cannot monopolize invisible work budgets.")
            public boolean globalFairnessMatrix = true;
            @Comment("Per-player fairness quantum for invisible survival-scale work.")
            public long globalFairnessPlayerQuantum = 512L;
            @Comment("Per-world fairness quantum for invisible survival-scale work.")
            public long globalFairnessWorldQuantum = 4096L;
            @Comment("Per-arena fairness quantum for invisible survival-scale work.")
            public long globalFairnessArenaQuantum = 2048L;
            @Comment("Per-region fairness quantum for invisible survival-scale work.")
            public long globalFairnessRegionQuantum = 1024L;
            @Comment("Maximum fairness carry multiplier. Higher values allow catch-up bursts without reordering visible gameplay.")
            public long globalFairnessMaxCarryMultiplier = 4L;
            @Comment("Enables tick-local cache for immutable/read-only duplicate computations. Entries clear every tick and never own live world state.")
            public boolean sharedReadOnlyCache = true;
            @Comment("Maximum entries in the tick-local shared read-only cache.")
            public int sharedReadOnlyCacheMaxEntries = 524288;
            @Comment("Enables AGC alpha14 scale kernel. It centralizes thousand-player survival budgets for invisible work only.")
            public boolean scaleKernel = true;
            @Comment("CPU percentage reserved for primary thread, plugins, Netty, GC and OS before AGC read-only workers receive capacity.")
            public int scaleKernelReserveCpuPercent = 22;
            @Comment("Abstract invisible-work units per usable CPU worker for the AGC scale kernel.")
            public long scaleKernelUnitsPerCore = 220000L;
            @Comment("Maximum read-only/helper workers AGC planning may assume when distributing scale-kernel budgets.")
            public int scaleKernelMaxWorkers = 96;
            @Comment("Enables tick-local region hotspot map for ordinary survival spawn/town hotspots.")
            public boolean regionHotspotMap = true;
            @Comment("Region hotspot cell size in blocks. This is only for read-only planning labels, not region thread ownership.")
            public int regionHotspotCellSizeBlocks = 64;
            @Comment("Players per hotspot cell before AGC shares duplicate read-only fanout and chunk-intent preparation.")
            public int regionHotspotPlayers = 48;
            @Comment("Maximum hotspot cells tracked for one tick.")
            public int regionHotspotMaxCells = 524288;
            @Comment("Enables alpha14 network fanout kernel for packet-shape planning without packet drops or per-connection reorder.")
            public boolean networkFanoutKernel = true;
            @Comment("Maximum network packet shapes retained per tick for fanout de-duplication.")
            public int networkFanoutKernelMaxShapes = 262144;
            @Comment("Enables alpha14 chunk pipeline kernel. It budgets chunk intent planning while preserving FIFO and Paper completion order.")
            public boolean chunkPipelineKernel = true;
            @Comment("Per-player chunk pipeline quantum for invisible intent planning.")
            public long chunkPipelinePerPlayerQuantum = 96L;
            @Comment("Enables alpha14 entity tracker kernel for read-only visibility candidate preparation. Entity ticks are never skipped.")
            public boolean entityTrackerKernel = true;
            @Comment("Per-player entity tracker candidate budget for read-only preparation.")
            public long entityTrackerKernelPerPlayerQuantum = 4096L;
            @Comment("Enables alpha14 thread-affinity translator that classifies read-only prepare vs ordered primary-thread commits.")
            public boolean threadAffinityTranslator = true;
            @Comment("Enables alpha14 plugin logic translator for compatibility-safe hardcoded logic categories.")
            public boolean pluginLogicTranslator = true;
            @Comment("Enables AGC alpha15 scale control plane that coordinates high-impact thousand-player optimisations without semantic invasion.")
            public boolean scaleControlPlane = true;
            @Comment("CPU percentage reserved before alpha15 heavy read-only optimisation lanes receive capacity.")
            public int scaleControlReserveCpuPercent = 24;
            @Comment("Abstract units per usable worker for alpha15 packet/chunk/entity/plugin optimisation lanes.")
            public long scaleControlUnitsPerCore = 260000L;
            @Comment("Enables alpha15 packet shape table for tick-local packet classification/fanout reuse without packet instance sharing.")
            public boolean packetShapeTable = true;
            @Comment("Maximum packet shapes retained per tick by the alpha15 packet shape table.")
            public int packetShapeTableMaxShapes = 524288;
            @Comment("Maximum receiver fanout considered when budgeting one packet shape in alpha15.")
            public int packetShapeTableMaxFanout = 4096;
            @Comment("Enables alpha15 player cohort table for shared read-only survival hotspot planning.")
            public boolean playerCohortTable = true;
            @Comment("Block size of alpha15 player cohorts. Cohorts are planning labels only, not visibility changes.")
            public int playerCohortCellSizeBlocks = 64;
            @Comment("Target players per alpha15 cohort before shared read-only planning becomes worthwhile.")
            public int playerCohortTargetPlayers = 32;
            @Comment("Maximum alpha15 player cohorts retained for a tick.")
            public int playerCohortMaxCohorts = 524288;
            @Comment("Enables alpha15 chunk intent compactor. It shares intent planning shapes while preserving FIFO and completion order.")
            public boolean chunkIntentCompactor = true;
            @Comment("Chunk region size used by alpha15 chunk intent compactor for read-only grouping.")
            public int chunkIntentCompactorRegionSizeChunks = 32;
            @Comment("Maximum alpha15 chunk intent shapes retained for one tick.")
            public int chunkIntentCompactorMaxShapes = 262144;
            @Comment("Enables alpha15 entity banding planner. It prepares read-only tracker candidates and never skips entity ticks.")
            public boolean entityBandingPlanner = true;
            @Comment("Maximum read-only entity candidates per player for alpha15 banding planner.")
            public int entityBandingMaxCandidatesPerPlayer = 8192;
            @Comment("Enables alpha15 memory locality planner for tick-local scratch budgeting without retaining live world state.")
            public boolean memoryLocalityPlanner = true;
            @Comment("Maximum tick-local scratch bytes budgeted by alpha15 memory locality planner.")
            public long memoryLocalityMaxScratchBytesPerTick = 134217728L;
            @Comment("Maximum scratch owners tracked per tick by alpha15 memory locality planner.")
            public int memoryLocalityMaxOwners = 65536;
            @Comment("Enables alpha15 encoding reuse planner for immutable helper planning before ordered connection writes.")
            public boolean encodingReusePlanner = true;
            @Comment("Maximum encoding shapes retained by alpha15 encoding reuse planner per tick.")
            public int encodingReuseMaxShapes = 262144;
            @Comment("Estimated byte threshold before alpha15 encoding helper planning is considered.")
            public int encodingReuseCompressionThresholdBytes = 512;
            @Comment("Enables alpha15 plugin backpressure bridge so helper work is routed without off-thread Bukkit mutation.")
            public boolean pluginBackpressureBridge = true;
            @Comment("Maximum pending translated plugin tasks before alpha15 bridge routes helper work back to ordered primary-thread commits.")
            public long pluginBackpressureMaxPendingTasks = 65536L;
            @Comment("Enables alpha15 hotspot eviction planner for tick-local read-only helper cache pressure.")
            public boolean hotspotEvictionPlanner = true;
            @Comment("Maximum alpha15 hotspot helper plans retained per tick.")
            public int hotspotEvictionMaxHotspots = 262144;
            @Comment("Players per hotspot before alpha15 read-only helper plan eviction pressure increases.")
            public int hotspotEvictionPlayers = 96;
        }
    }
    static void set(final GlobalConfiguration instance) {
        GlobalConfiguration.instance = instance;
    }

    @Setting(Configuration.VERSION_FIELD)
    public int version = CURRENT_VERSION;

    public Messages messages;

    public class Messages extends ConfigurationPart {
        public Kick kick;

        public class Kick extends ConfigurationPart {
            public Component authenticationServersDown = Component.translatable("multiplayer.disconnect.authservers_down");
            public Component connectionThrottle = Component.text("Connection throttled! Please wait before reconnecting.");
            public Component flyingPlayer = Component.translatable("multiplayer.disconnect.flying");
            public Component flyingVehicle = Component.translatable("multiplayer.disconnect.flying");
        }

        public Component noPermission = Component.text("I'm sorry, but you do not have permission to perform this command. Please contact the server administrators if you believe that this is in error.", NamedTextColor.RED);
        public boolean useDisplayNameInQuitMessage = false;
    }

    public Spark spark;

    public class Spark extends ConfigurationPart {
        public boolean enabled = true;
        public boolean enableImmediately = false;
    }

    public Proxies proxies;

    public class Proxies extends ConfigurationPart {
        public BungeeCord bungeeCord;

        public class BungeeCord extends ConfigurationPart {
            public boolean onlineMode = true;
        }

        public Velocity velocity;

        public class Velocity extends ConfigurationPart {
            public boolean enabled = false;
            public boolean onlineMode = true;
            public String secret = "";

            @PostProcess
            private void postProcess() {
                if (!this.enabled) return;

                final String environmentSourcedVelocitySecret = System.getenv("PAPER_VELOCITY_SECRET");
                if (environmentSourcedVelocitySecret != null && !environmentSourcedVelocitySecret.isEmpty()) {
                    this.secret = environmentSourcedVelocitySecret;
                }

                if (this.secret.isEmpty()) {
                    LOGGER.error("Velocity is enabled, but no secret key was specified. A secret key is required. Disabling velocity...");
                    this.enabled = false;
                }
            }
        }
        public boolean proxyProtocol = false;
        public boolean isProxyOnlineMode() {
            return org.bukkit.Bukkit.getOnlineMode() || (org.spigotmc.SpigotConfig.bungee && this.bungeeCord.onlineMode) || (this.velocity.enabled && this.velocity.onlineMode);
        }
    }

    public Console console;

    public class Console extends ConfigurationPart {
        public boolean enableBrigadierHighlighting = true;
        public boolean enableBrigadierCompletions = true;
        public boolean hasAllPermissions = false;
    }

    public Watchdog watchdog;

    public class Watchdog extends ConfigurationPart {
        public int earlyWarningEvery = 5000;
        public int earlyWarningDelay = 10000;
    }

    public SpamLimiter spamLimiter;

    public class SpamLimiter extends ConfigurationPart {
        public int tabSpamIncrement = 1;
        public int tabSpamLimit = 500;
        public int recipeSpamIncrement = 1;
        public int recipeSpamLimit = 20;
        public IntOr.Disabled incomingPacketThreshold = new IntOr.Disabled(OptionalInt.of(300));
    }

    public UnsupportedSettings unsupportedSettings;

    public class UnsupportedSettings extends ConfigurationPart {
        @Comment("This setting allows for exploits related to end portals, for example sand duping")
        public boolean allowUnsafeEndPortalTeleportation = false;
        @Comment("This setting controls the ability to enable dupes related to tripwires.")
        public boolean skipTripwireHookPlacementValidation = false;
        @Comment("This setting controls if players should be able to break bedrock, end portals and other intended to be permanent blocks.")
        public boolean allowPermanentBlockBreakExploits = false;
        @Comment("This setting controls if player should be able to use TNT duplication, but this also allows duplicating carpet, rails and potentially other items")
        public boolean allowPistonDuplication = false;
        public boolean performUsernameValidation = true;
        @Comment("This setting controls if players should be able to create headless pistons.")
        public boolean allowHeadlessPistons = false;
        @Comment("This setting controls if the vanilla damage tick should be skipped if damage was blocked via a shield.")
        public boolean skipVanillaDamageTickWhenShieldBlocked = false;
        @Comment("This setting controls if equipment should be updated when handling certain player actions.")
        public boolean updateEquipmentOnPlayerActions = true;
        @Comment("This setting controls what item data components don't need to be sanitized in oversized item obfuscation. Adding them re-enables exploits, but may be needed for certain resource packs. (Expected: minecraft:container, minecraft:charged_projectiles and minecraft:bundle_contents)")
        public OversizedItemComponentSanitizer.AssetOversizedItemComponentSanitizerConfiguration oversizedItemComponentSanitizer = new OversizedItemComponentSanitizer.AssetOversizedItemComponentSanitizerConfiguration(Set.of());
    }

    public Commands commands;

    public class Commands extends ConfigurationPart {
        public boolean suggestPlayerNamesWhenNullTabCompletions = true;
        @Comment("Allow mounting entities to a player in the Vanilla '/ride' command.")
        public boolean rideCommandAllowPlayerAsVehicle = false;
    }

    public Time time;

    public class Time extends ConfigurationPart {
        public boolean affectsAllWorlds = false;
    }

    public Scoreboards scoreboards;

    public class Scoreboards extends ConfigurationPart {
        public boolean trackPluginScoreboards = false;
        public boolean saveEmptyScoreboardTeams = true;
    }

    @SuppressWarnings("unused") // used in postProcess
    public ChunkSystem chunkSystem;

    public class ChunkSystem extends ConfigurationPart {

        public int ioThreads = -1;
        public int workerThreads = -1;

        @PostProcess
        private void postProcess() {
            ca.spottedleaf.moonrise.common.util.MoonriseCommon.adjustWorkerThreads(this.workerThreads, this.ioThreads);
        }
    }

    public ItemValidation itemValidation;

    public class ItemValidation extends ConfigurationPart {
        public int displayName = 8192;
        public int loreLine = 8192;
        public Book book;

        public class Book extends ConfigurationPart {
            public int title = 8192;
            public int author = 8192;
            public int page = 16384;
        }

        public BookSize bookSize;

        public class BookSize extends ConfigurationPart {
            public IntOr.Disabled pageMax = new IntOr.Disabled(OptionalInt.of(2560)); // TODO this appears to be a duplicate setting with one above
            public double totalMultiplier = 0.98D; // TODO this should probably be merged into the above inner class
        }
        public boolean resolveSelectorsInBooks = false;
    }

    public PacketLimiter packetLimiter;

    public class PacketLimiter extends ConfigurationPart {
        public Component kickMessage = Component.translatable("disconnect.exceeded_packet_rate", NamedTextColor.RED);
        public PacketLimit allPackets = new PacketLimit(7.0, 500.0, PacketLimit.ViolateAction.KICK);
        public Map<@WriteKeyBack Class<? extends Packet<?>>, PacketLimit> overrides = Map.of(ServerboundPlaceRecipePacket.class, new PacketLimit(4.0, 5.0, PacketLimit.ViolateAction.DROP));

        @ConfigSerializable
        public record PacketLimit(@Required double interval, @Required double maxPacketRate, ViolateAction action) {
            public PacketLimit(final double interval, final double maxPacketRate, final @Nullable ViolateAction action) {
                this.interval = interval;
                this.maxPacketRate = maxPacketRate;
                this.action = Objects.requireNonNullElse(action, ViolateAction.KICK);
            }

            public boolean isEnabled() {
                return this.interval > 0.0 && this.maxPacketRate > 0.0;
            }

            public enum ViolateAction {
                KICK,
                DROP;
            }
        }
    }

    public Collisions collisions;

    public class Collisions extends ConfigurationPart {
        public boolean enablePlayerCollisions = true;
        public boolean sendFullPosForHardCollidingEntities = true;
    }

    public PlayerAutoSave playerAutoSave;


    public class PlayerAutoSave extends ConfigurationPart {
        public int rate = -1;
        private int maxPerTick = -1;
        public int maxPerTick() {
            if (this.maxPerTick < 0) {
                return (this.rate == 1 || this.rate > 100) ? 10 : 20;
            }
            return this.maxPerTick;
        }
    }

    public Misc misc;

    public class Misc extends ConfigurationPart {

        @SuppressWarnings("unused") // used in postProcess
        public ChatThreads chatThreads;
        public class ChatThreads extends ConfigurationPart {
            private int chatExecutorCoreSize = -1;
            private int chatExecutorMaxSize = -1;

            @PostProcess
            private void postProcess() {
                //noinspection ConstantConditions
                if (net.minecraft.server.MinecraftServer.getServer() == null) return; // In testing env, this will be null here
                int _chatExecutorMaxSize = (this.chatExecutorMaxSize <= 0) ? Integer.MAX_VALUE : this.chatExecutorMaxSize; // This is somewhat dumb, but, this is the default, do we cap this?;
                int _chatExecutorCoreSize = Math.max(this.chatExecutorCoreSize, 0);

                if (_chatExecutorMaxSize < _chatExecutorCoreSize) {
                    _chatExecutorMaxSize = _chatExecutorCoreSize;
                }

                java.util.concurrent.ThreadPoolExecutor executor = (java.util.concurrent.ThreadPoolExecutor) net.minecraft.server.MinecraftServer.getServer().chatExecutor;
                executor.setCorePoolSize(_chatExecutorCoreSize);
                executor.setMaximumPoolSize(_chatExecutorMaxSize);
            }
        }
        public int maxJoinsPerTick = 5;
        public boolean sendFullPosForItemEntities = false;
        public boolean loadPermissionsYmlBeforePlugins = true;
        @Constraints.Min(4)
        public int regionFileCacheSize = 256;
        @Comment("See https://luckformula.emc.gs")
        public boolean useAlternativeLuckFormula = false;
        public boolean useDimensionTypeForCustomSpawners = false;
        public boolean strictAdvancementDimensionCheck = false;
        public IntOr.Default compressionLevel = IntOr.Default.USE_DEFAULT;
        @Comment("Defines the leniency distance added on the server to the interaction range of a player when validating interact packets.")
        public DoubleOr.Default clientInteractionLeniencyDistance = DoubleOr.Default.USE_DEFAULT;
        @Comment("Defines how many orbs groups can exist in an area.")
        @Constraints.Min(1)
        public IntOr.Default xpOrbGroupsPerArea = IntOr.Default.USE_DEFAULT;
        @Comment("See Fix MC-163962; prevent villager demand from going negative.")
        public boolean preventNegativeVillagerDemand = false;
        @Comment("Whether the nether dimension is enabled and will be loaded.")
        public boolean enableNether = true;
        @Comment("Keeps Paper's fix for MC-159283 enabled. Disable to use vanilla End ring terrain.")
        public boolean fixFarEndTerrainGeneration = true;
        @Comment("Fix for MC-301114. This removes the oldest combat entry when it hits the cap, to fix a memory leak on constant entity damage.")
        public IntOr.Disabled maxTrackingCombatEntries = new IntOr.Disabled(OptionalInt.of(10240));
    }

    public BlockUpdates blockUpdates;

    public class BlockUpdates extends ConfigurationPart {
        public boolean disableNoteblockUpdates = false;
        public boolean disableTripwireUpdates = false;
        public boolean disableChorusPlantUpdates = false;
        public boolean disableMushroomBlockUpdates = false;
    }

    public Anticheat anticheat;

    public class Anticheat extends ConfigurationPart {

        public Obfuscation obfuscation;

        public class Obfuscation extends ConfigurationPart {
            public Items items;

            public class Items extends ConfigurationPart {

                public boolean enableItemObfuscation = false;
                public ItemObfuscationBinding.AssetObfuscationConfiguration allModels = new ItemObfuscationBinding.AssetObfuscationConfiguration(
                    true,
                    Set.of(DataComponents.LODESTONE_TRACKER),
                    Set.of()
                );

                public Map<Identifier, ItemObfuscationBinding.AssetObfuscationConfiguration> modelOverrides = Map.of(
                    Objects.requireNonNull(net.minecraft.world.item.Items.ELYTRA.components().get(DataComponents.ITEM_MODEL)),
                    new ItemObfuscationBinding.AssetObfuscationConfiguration(
                        true,
                        Set.of(DataComponents.DAMAGE),
                        Set.of()
                    )
                );

                public transient ItemObfuscationBinding binding;

                @PostProcess
                public void bindDataSanitizer() {
                    this.binding = new ItemObfuscationBinding(this);
                }
            }
        }
    }

    public UpdateChecker updateChecker;

    public class UpdateChecker extends ConfigurationPart {
        public boolean enabled = true;
    }
}
