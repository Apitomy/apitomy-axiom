package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.config.AxiomHome;
import io.quarkus.runtime.Startup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encryption key is loaded (or created) when Axiom starts, and a key that cannot be used stops startup.
 */
class EncryptionServiceTest {

    @TempDir
    Path home;

    private String previousHome;

    @BeforeEach
    void useTemporaryHome() {
        previousHome = System.getProperty(AxiomHome.PROPERTY);
        System.setProperty(AxiomHome.PROPERTY, home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) {
            System.clearProperty(AxiomHome.PROPERTY);
        } else {
            System.setProperty(AxiomHome.PROPERTY, previousHome);
        }
    }

    @Test
    void createsAKeyOnFirstStartAndUsesItAfterwards() {
        EncryptionService first = new EncryptionService();
        first.init();
        assertTrue(Files.exists(home.resolve("secret.key")));
        String encrypted = first.encrypt("hello");

        EncryptionService second = new EncryptionService();
        second.init();
        assertEquals("hello", second.decrypt(encrypted));
    }

    @Test
    void aKeyOfTheWrongLengthStopsStartup() throws IOException {
        Files.write(home.resolve("secret.key"), new byte[] {1, 2, 3});
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new EncryptionService().init());
        assertTrue(e.getMessage().contains(home.resolve("secret.key").toString()), e.getMessage());
        assertTrue(e.getMessage().contains("not a valid 256-bit key"), e.getMessage());
    }

    @Test
    void anUnreadableKeyStopsStartup() throws IOException {
        Files.createDirectories(home.resolve("secret.key"));   // a directory cannot be read as a key
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new EncryptionService().init());
        assertTrue(e.getMessage().startsWith("Cannot load the encryption key from "), e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void theKeyIsLoadedAtStartupNotOnFirstUse() {
        assertTrue(EncryptionService.class.isAnnotationPresent(Startup.class),
                "EncryptionService must be created eagerly so a bad key stops startup");
    }
}
