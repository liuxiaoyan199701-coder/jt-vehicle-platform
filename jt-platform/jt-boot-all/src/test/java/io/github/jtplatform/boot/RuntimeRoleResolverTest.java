package io.github.jtplatform.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jtplatform.boot.runtime.RuntimeRole;
import io.github.jtplatform.boot.runtime.RuntimeRoleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeRoleResolverTest {
    @AfterEach
    void clearRoleProperty() {
        System.clearProperty("jt.runtime.role");
    }

    @Test
    void defaultsToAllInOne() {
        assertEquals(RuntimeRole.ALL, RuntimeRoleResolver.resolve(new String[0]));
    }

    @Test
    void resolvesSystemPropertyAndCommandLineOverride() {
        System.setProperty("jt.runtime.role", "media");
        assertEquals(RuntimeRole.MEDIA, RuntimeRoleResolver.resolve(new String[0]));
        assertEquals(RuntimeRole.API,
                RuntimeRoleResolver.resolve(new String[] {"--jt.runtime.role=api"}));
    }

    @Test
    void supportsSeparatedCommandLineValue() {
        assertEquals(RuntimeRole.SIGNAL,
                RuntimeRoleResolver.resolve(new String[] {"--jt.runtime.role", "signal"}));
    }

    @Test
    void rejectsUnknownRole() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeRoleResolver.resolve(new String[] {"--jt.runtime.role=worker"}));
    }
}
