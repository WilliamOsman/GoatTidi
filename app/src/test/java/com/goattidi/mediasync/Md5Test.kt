package com.goattidi.mediasync

import com.goattidi.mediasync.data.hash.Md5
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class Md5Test {

    @Test
    fun `known vector`() {
        val md5 = Md5.of(ByteArrayInputStream("hello world".toByteArray()))
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", md5)
    }

    @Test
    fun `empty input`() {
        val md5 = Md5.of(ByteArrayInputStream(ByteArray(0)))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5)
    }

    @Test
    fun `file variant matches stream variant`() {
        val file = File.createTempFile("md5test", ".bin").apply {
            deleteOnExit()
            writeBytes(ByteArray(200_000) { (it % 251).toByte() })
        }
        assertEquals(Md5.of(file.inputStream()), Md5.of(file))
    }
}
