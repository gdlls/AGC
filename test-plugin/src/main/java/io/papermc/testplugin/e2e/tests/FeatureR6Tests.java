package io.papermc.testplugin.e2e.tests;

import io.papermc.testplugin.e2e.E2ETest;
import io.papermc.testplugin.e2e.TestContext;
import io.papermc.testplugin.e2e.TestRegistry;

public class FeatureR6Tests {
    public void register(TestRegistry registry) {
        // Tier 1 Tests
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T1_1"; }
            @Override public String getName() { return "R6 Tier 1 - Collision Resolver Load Verification"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                context.assertNotNull(clazz, "MeteusSpatialGrid compatibility alias should be loadable.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T1_2"; }
            @Override public String getName() { return "R6 Tier 1 - Bounding Box Scale Reader"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method getBoxScale = clazz.getDeclaredMethod("getBoundingBoxScale");
                context.assertNotNull(getBoxScale, "getBoundingBoxScale method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T1_3"; }
            @Override public String getName() { return "R6 Tier 1 - Collision Aspect Ratio Field"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Field ratioField = clazz.getDeclaredField("collisionAspectRatio");
                context.assertNotNull(ratioField, "collisionAspectRatio field must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T1_4"; }
            @Override public String getName() { return "R6 Tier 1 - Bounding Box Extent Checker"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method hasCustomExtents = clazz.getDeclaredMethod("hasCustomExtents", org.bukkit.entity.Entity.class);
                context.assertNotNull(hasCustomExtents, "hasCustomExtents method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T1_5"; }
            @Override public String getName() { return "R6 Tier 1 - Hitbox Expansion Limit"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Field expansionLimit = clazz.getDeclaredField("maxHitboxExpansion");
                context.assertNotNull(expansionLimit, "maxHitboxExpansion field must exist.");
            }
        });

        // Tier 2 Tests
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T2_1"; }
            @Override public String getName() { return "R6 Tier 2 - Calculate Hitbox Extents"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method getExtents = clazz.getDeclaredMethod("getHitboxExtents", org.bukkit.entity.Entity.class);
                context.assertNotNull(getExtents, "getHitboxExtents method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T2_2"; }
            @Override public String getName() { return "R6 Tier 2 - Check Ray-Box Intersect"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                Class<?> rayClass = Class.forName("org.bukkit.util.Ray");
                java.lang.reflect.Method intersects = clazz.getDeclaredMethod("intersectsRay", org.bukkit.entity.Entity.class, rayClass);
                context.assertNotNull(intersects, "intersectsRay method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T2_3"; }
            @Override public String getName() { return "R6 Tier 2 - Register Custom Hitbox Dimensions"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method registerBox = clazz.getDeclaredMethod("registerCustomHitbox", org.bukkit.entity.EntityType.class, double.class, double.class);
                context.assertNotNull(registerBox, "registerCustomHitbox method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T2_4"; }
            @Override public String getName() { return "R6 Tier 2 - Unregister Custom Hitbox Dimensions"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                java.lang.reflect.Method unregisterBox = clazz.getDeclaredMethod("unregisterCustomHitbox", org.bukkit.entity.EntityType.class);
                context.assertNotNull(unregisterBox, "unregisterCustomHitbox method must exist.");
            }
        });
        registry.register(new E2ETest() {
            @Override public String getId() { return "R6_T2_5"; }
            @Override public String getName() { return "R6 Tier 2 - Resolve Multiple Overlapping Hitboxes"; }
            @Override public void run(TestContext context) throws Exception {
                Class<?> clazz = context.checkClassExists("net.minecraft.server.MeteusSpatialGrid");
                Class<?> rayClass = Class.forName("org.bukkit.util.Ray");
                java.lang.reflect.Method resolve = clazz.getDeclaredMethod("resolveOverlappingHitboxes", rayClass, double.class);
                context.assertNotNull(resolve, "resolveOverlappingHitboxes method must exist.");
            }
        });
    }
}
