package com.simplechat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import mychat.framework.profile.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 角色卡的读写：读不到也要给空对象，不能返回 null。 */
@RunWith(RobolectricTestRunner::class)
class ProfileStoreTest {

    private fun store() = SettingsStore(ApplicationProvider.getApplicationContext<Context>())

    /** DataStore 是进程级单例：同一 JVM 里的用例会看到彼此的写入，先清干净。 */
    @Before
    fun reset() = kotlinx.coroutines.runBlocking {
        store().saveUserProfile(UserProfile())
    }

    @Test
    fun emptyProfileIsReadable() = runTest {
        val profile = store().userProfile()
        assertNotNull(profile)
        assertEquals("", profile.role)
        assertEquals("平衡", profile.style)
        assertEquals(emptyList<String>(), profile.tech)
        assertEquals(emptyList<String>(), profile.avoid)
    }

    @Test
    fun roundTrip() = runTest {
        val store = store()
        store.saveUserProfile(
            UserProfile(
                role = "独立开发者",
                tech = listOf("Kotlin", "Android"),
                style = "极简",
                avoid = listOf("空话"),
            )
        )

        val read = store().userProfile()
        assertEquals("独立开发者", read.role)
        assertEquals(listOf("Kotlin", "Android"), read.tech)
        assertEquals("极简", read.style)
        assertEquals(listOf("空话"), read.avoid)
    }
}
