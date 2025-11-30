package com.madanala.tern

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import io.mockk.MockKAnnotations
import io.mockk.unmockkAll

/**
 * Base class for all unit tests.
 * Provides:
 * - Coroutine test dispatcher setup (MainDispatcherRule)
 * - MockK initialization and cleanup
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseTest {

    val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    open fun setup() {
        Dispatchers.setMain(testDispatcher)
        MockKAnnotations.init(this)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }
}
