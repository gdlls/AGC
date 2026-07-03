package io.papermc.testplugin.e2e;

public interface E2ETest {
    String getId();
    String getName();
    void run(TestContext context) throws Exception;
}
