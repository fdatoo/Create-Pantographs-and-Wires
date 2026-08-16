package de.mrjulsen.paw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class TestHarnessSmokeTest {
    @Test
    void junitRunsOnJava21() {
        assertEquals(21, Runtime.version().feature());
    }
}
