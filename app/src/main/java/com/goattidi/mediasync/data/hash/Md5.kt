package com.goattidi.mediasync.data.hash

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Md5 {

    /** Streams the input and returns the lowercase hex MD5. Closes the stream. */
    fun of(input: InputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(file: File): String = of(file.inputStream())
}
