package com.frank.videosubtitle.util

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertSame
import org.junit.Test

class DispatcherProviderTest {

    @Test
    fun `default provider exposes standard io and default dispatchers`() {
        val provider = DefaultDispatcherProvider()
        assertSame(Dispatchers.IO, provider.io)
        assertSame(Dispatchers.Default, provider.default)
    }
}
