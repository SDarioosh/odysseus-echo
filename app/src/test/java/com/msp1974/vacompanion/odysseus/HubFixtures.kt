package com.msp1974.vacompanion.odysseus

// Odysseus fork: real answers of the hub (fictional data), recorded over HTTP in the sandbox
// against hub branch work/b2 (2026-11-06): app/src/test/resources/odysseus/hub/<contract>_*.json.
// The *HubFixturesTest classes check that the parsers read the hub's actual output as intended,
// not only the plan's examples. When the hub's answer changes, record it again and update them.

object HubFixtures {
    fun load(name: String): String {
        val url = HubFixtures::class.java.classLoader?.getResource("odysseus/hub/$name")
            ?: error("missing fixture odysseus/hub/$name")
        return url.readText()
    }
}
