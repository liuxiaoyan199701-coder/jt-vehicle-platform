package io.github.jtplatform.boot.registry.contract;

import io.github.jtplatform.common.auth.InMemoryStreamTokenStore;
import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.port.InMemoryMediaInstanceRegistry;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.StreamRegistry;
import java.security.SecureRandom;
import java.time.Clock;

class InMemoryStreamRegistryContractTest extends StreamRegistryContractTest {
    @Override
    protected StreamRegistry newRegistry() {
        return new InMemoryStreamRegistry();
    }
}

class InMemoryMediaInstanceRegistryContractTest extends MediaInstanceRegistryContractTest {
    @Override
    protected MediaInstanceRegistry newRegistry() {
        return new InMemoryMediaInstanceRegistry();
    }
}

class InMemoryStreamTokenStoreContractTest extends StreamTokenStoreContractTest {
    @Override
    protected StreamTokenStore newStore(Clock clock) {
        return new InMemoryStreamTokenStore(new SecureRandom(), clock);
    }
}
