package com.msp1974.vacompanion.odysseus.home.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicImagesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun lruEvictsTheLeastRecentlyUsedBySize() {
        val lru = ByteLru<ByteArray>(100) { it.size.toLong() }
        lru.put("a", ByteArray(40))
        lru.put("b", ByteArray(40))
        lru.get("a") // a is now the most recent
        lru.put("c", ByteArray(40))
        assertEquals(listOf("a", "c"), lru.keys())
        assertEquals(80, lru.bytes())
        // One big value pushes out several.
        lru.put("d", ByteArray(90))
        assertEquals(listOf("d"), lru.keys())
        assertEquals(90, lru.bytes())
    }

    @Test
    fun lruReplacesAndRefusesTooBig() {
        val lru = ByteLru<ByteArray>(100) { it.size.toLong() }
        lru.put("a", ByteArray(30))
        lru.put("a", ByteArray(50))
        assertEquals(50, lru.bytes())
        lru.put("huge", ByteArray(101))
        assertNull(lru.get("huge"))
        assertEquals(listOf("a"), lru.keys())
        lru.put("a", ByteArray(101))
        assertEquals(0, lru.bytes())
        assertTrue(lru.keys().isEmpty())
    }

    @Test
    fun diskKeepsTheNewestFiles() {
        var now = 1_000_000L
        val disk = DiskCache(tmp.root, maxFiles = 3, clock = { now }, trimEvery = 1)
        for (i in 1..3) {
            now += 1_000
            disk.write("https://hub.example.test/music/image?u=$i", byteArrayOf(i.toByte()))
        }
        now += 1_000
        // Reading 1 makes it recent; writing 4 then removes 2, the least recently used.
        assertArrayEquals(byteArrayOf(1), disk.read("https://hub.example.test/music/image?u=1"))
        now += 1_000
        disk.write("https://hub.example.test/music/image?u=4", byteArrayOf(4))
        assertEquals(3, disk.count())
        assertNull(disk.read("https://hub.example.test/music/image?u=2"))
        assertArrayEquals(byteArrayOf(3), disk.read("https://hub.example.test/music/image?u=3"))
        assertArrayEquals(byteArrayOf(4), disk.read("https://hub.example.test/music/image?u=4"))
    }

    @Test
    fun diskTrimsOnTheFirstWriteThenEveryNth() {
        var now = 1_000_000L
        // An old folder over the limit is trimmed by the first write.
        val old = DiskCache(tmp.root, maxFiles = 100, clock = { now }, trimEvery = 1000)
        for (i in 1..5) {
            now += 1_000
            old.write("https://hub.example.test/music/image?u=$i", byteArrayOf(i.toByte()))
        }
        assertEquals(5, old.count())
        val disk = DiskCache(tmp.root, maxFiles = 3, clock = { now }, trimEvery = 4)
        now += 1_000
        disk.write("https://hub.example.test/music/image?u=6", byteArrayOf(6))
        assertEquals(3, disk.count())
        // The next three writes aren't trimmed; the fourth is.
        for (i in 7..9) {
            now += 1_000
            disk.write("https://hub.example.test/music/image?u=$i", byteArrayOf(i.toByte()))
        }
        assertEquals(6, disk.count())
        now += 1_000
        disk.write("https://hub.example.test/music/image?u=10", byteArrayOf(10))
        assertEquals(3, disk.count())
        assertArrayEquals(byteArrayOf(10), disk.read("https://hub.example.test/music/image?u=10"))
        assertNull(disk.read("https://hub.example.test/music/image?u=7"))
    }

    @Test
    fun fileNamesAreHashes() {
        val disk = DiskCache(tmp.root)
        assertEquals(64, disk.fileOf("/api/media_player_proxy/x?token=y").name.length)
        assertNull(disk.read("missing"))
    }

    @Test
    fun buckets() {
        assertEquals(64, MusicImages.bucket(48))
        assertEquals(128, MusicImages.bucket(96))
        assertEquals(256, MusicImages.bucket(200))
        assertEquals(512, MusicImages.bucket(900))
    }
}
