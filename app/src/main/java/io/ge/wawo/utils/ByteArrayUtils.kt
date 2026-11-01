@file:Suppress("NOTHING_TO_INLINE")

package io.ge.wawo.utils

import java.nio.ByteBuffer

inline fun ByteArray.toByteBuffer() = ByteBuffer.wrap(this)

