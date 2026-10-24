package com.pishi.termbridge

import com.pishi.termbridge.transport.RemotePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {
    @Test
    fun normalizeResolvesDotAndDotDot() {
        assertEquals("/a/c", RemotePath.normalize("/a/b/../c"))
        assertEquals("/a/b", RemotePath.normalize("/a/./b"))
        assertEquals("/", RemotePath.normalize("/"))
        assertEquals("/a", RemotePath.normalize("/a/"))
    }

    @Test
    fun normalizeClampsAtRoot() {
        assertEquals("/etc", RemotePath.normalize("/../etc"))
        assertEquals("/", RemotePath.normalize("/../../"))
    }

    @Test
    fun normalizeRejectsNonAbsoluteAndNul() {
        assertNull(RemotePath.normalize("relative/path"))
        assertNull(RemotePath.normalize("/a\u0000b"))
    }

    @Test
    fun parentWorks() {
        assertEquals("/a", RemotePath.parent("/a/b"))
        assertEquals("/", RemotePath.parent("/a"))
        assertEquals("/", RemotePath.parent("/"))
    }

    @Test
    fun joinValidatesName() {
        assertEquals("/a/b.txt", RemotePath.join("/a", "b.txt"))
        assertEquals("/b.txt", RemotePath.join("/", "b.txt"))
        assertNull(RemotePath.join("/a", "../evil"))
        assertNull(RemotePath.join("/a", "a/b"))
        assertNull(RemotePath.join("/a", ""))
        assertNull(RemotePath.join("/a", "."))
    }

    @Test
    fun nameValidation() {
        assertTrue(RemotePath.isValidName("my file.txt"))
        assertTrue(RemotePath.isValidName("فایل.txt"))
        assertFalse(RemotePath.isValidName(".."))
        assertFalse(RemotePath.isValidName("a/b"))
        assertFalse(RemotePath.isValidName("   "))
    }

    @Test
    fun displayName() {
        assertEquals("file.txt", RemotePath.displayName("/a/b/file.txt"))
        assertEquals("/", RemotePath.displayName("/"))
    }
}
