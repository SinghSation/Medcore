package com.medcore

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class MedcoreApiApplicationTests {

    @Test
    fun `spring context loads`() {
        // Smoke test: the application context boots without failure.
        // Domain tests live alongside their modules as they are introduced.
    }
}
