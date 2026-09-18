package io.papermc.paper.configuration;

import io.papermc.paper.agc.AgcCapabilityMatrix;
import io.papermc.paper.configuration.mapping.InnerClassFieldDiscoverer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public final class AgcConfigurations {

    public static final String FILE_NAME = "agc.yml";

    private AgcConfigurations() {
    }

    public static GlobalConfiguration.Agc load(final Path directory, final ConfigurationNode legacy) throws ConfigurateException {
        final Path file = directory.resolve(FILE_NAME);
        final ObjectMapper.Factory factory = ObjectMapper.factoryBuilder()
            .addDiscoverer(InnerClassFieldDiscoverer.globalConfig(List.of()))
            .build();
        final YamlConfigurationLoader loader = ConfigurationLoaders.naturallySorted()
            .path(file)
            .defaultOptions(options -> options.serializers(serializers -> serializers
                .register(type -> type instanceof Class<?> clazz && ConfigurationPart.class.isAssignableFrom(clazz), factory.asTypeSerializer())))
            .build();
        final boolean creating = Files.notExists(file);
        try {
            final ConfigurationNode node;
            if (creating) {
                node = CommentedConfigurationNode.root(loader.defaultOptions());
                if (!legacy.virtual()) {
                    node.from(legacy);
                }
            } else {
                node = loader.load();
            }
            if (!node.isMap() && node.raw() != null) {
                throw new SerializationException(node, GlobalConfiguration.Agc.class, "Expected an AGC configuration mapping");
            }
            final ConfigurationNode defaults = loader.createNode();
            final GlobalConfiguration.Agc defaultInstance = new GlobalConfiguration.Agc();
            defaults.set(GlobalConfiguration.Agc.class, defaultInstance);
            if (node.raw() != null) {
                validate(node, defaults);
            }
            node.mergeFrom(defaults);
            final String mode = node.node("mode").getString(defaultInstance.mode).toLowerCase(java.util.Locale.ROOT);
            if (!mode.equals("agc_aggressive") && !mode.equals("unified") && !mode.equals("agc_baseline") && !mode.equals("vanilla")) {
                throw new SerializationException(node.node("mode"), String.class, "Unknown AGC operating mode: " + mode);
            }
            node.node("mode").set(mode);
            final GlobalConfiguration.Agc configuration = node.require(GlobalConfiguration.Agc.class);
            if (configuration.performance.parallelWorldTickMinWorlds < 2) {
                throw new SerializationException(node.node("performance", "parallel-world-tick-min-worlds"), Integer.class, "Must be at least 2");
            }
            if (creating) {
                loader.save(node);
            }
            return configuration;
        } catch (ConfigurateException exception) {
            throw new ConfigurateException("Could not load " + file + ": " + exception.getMessage(), exception);
        }
    }

    private static void validate(final ConfigurationNode node, final ConfigurationNode defaults) throws SerializationException {
        if (node.virtual()) {
            return;
        }
        if (defaults.isMap()) {
            if (!node.isMap()) {
                throw new SerializationException(node, Object.class, "Expected a mapping");
            }
            for (final var entry : defaults.childrenMap().entrySet()) {
                validate(node.node(entry.getKey()), entry.getValue());
            }
            return;
        }
        final Object expected = defaults.raw();
        final Object actual = node.raw();
        if (expected instanceof Boolean && !(actual instanceof Boolean)) {
            throw new SerializationException(node, Boolean.class, "Expected true or false");
        }
        if (expected instanceof Number) {
            if (!(actual instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) {
                throw new SerializationException(node, Number.class, "Expected a finite, non-negative number");
            }
            if ((expected instanceof Integer || expected instanceof Long) && number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new SerializationException(node, Number.class, "Expected a whole number");
            }
        }
        if (expected instanceof String && !(actual instanceof String)) {
            throw new SerializationException(node, String.class, "Expected a string");
        }
    }
}
