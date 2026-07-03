package io.papermc.testplugin.e2e;

import io.papermc.testplugin.e2e.tests.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestRegistry {
    private static final TestRegistry INSTANCE = new TestRegistry();
    private final List<E2ETest> tests = new ArrayList<>();

    private TestRegistry() {
        registerAllTests();
    }

    public static TestRegistry getInstance() {
        return INSTANCE;
    }

    private void registerAllTests() {
        new FeatureR1Tests().register(this);
        new FeatureR2Tests().register(this);
        new FeatureR3Tests().register(this);
        new FeatureR4Tests().register(this);
        new FeatureR5Tests().register(this);
        new FeatureR6Tests().register(this);
        new FeatureR7Tests().register(this);
        new FeatureR8Tests().register(this);
        new FeatureR9Tests().register(this);
        new FeatureR10Tests().register(this);
        new FeatureR11Tests().register(this);
        new FeatureR12Tests().register(this);
        new FeatureR13Tests().register(this);
        new FeatureR14Tests().register(this);
        new FeatureR15Tests().register(this);
        new FeatureR16Tests().register(this);
        new FeatureR17Tests().register(this);
        new FeatureR18Tests().register(this);
        new FeatureR19Tests().register(this);
        new FeatureR20Tests().register(this);
        new FeatureR21Tests().register(this);
        new FeatureR22Tests().register(this);
        new FeatureR23Tests().register(this);
        new FeatureR24Tests().register(this);
        new FeatureR25Tests().register(this);
        new FeatureR26Tests().register(this);
        new FeatureR27Tests().register(this);
        new FeatureR28Tests().register(this);
        new FeatureR29Tests().register(this);
        new FeatureR30Tests().register(this);
        new FeatureR31Tests().register(this);
        new FeatureR32Tests().register(this);
        new Tier3Tests().register(this);
        new Tier4Tests().register(this);
    }

    public void register(E2ETest test) {
        tests.add(test);
    }

    public List<E2ETest> getTests() {
        return Collections.unmodifiableList(tests);
    }
}
