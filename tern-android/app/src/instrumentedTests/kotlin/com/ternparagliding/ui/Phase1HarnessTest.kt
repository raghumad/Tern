package com.ternparagliding.ui

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ternparagliding.TernParaglidingActivity
import com.ternparagliding.redux.CountryPreloadMiddleware
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.MapTestHelper
import com.ternparagliding.utils.MapVisualTest
import com.ternparagliding.utils.cache.CacheManager
import com.ternparagliding.utils.geo.CountryUtils
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint

/**
 * Phase 1 (trustworthy harness) regression tests.
 *
 *  1. The MapLibre projection hook resolves geo points to screen pixels with
 *     the right orientation — the capability that lets gesture tests act at a
 *     lat/lon instead of dispatching Redux actions tautologically.
 *  2. The rebuilt [MapTestHelper] gesture helpers drive a real touch on the map
 *     end-to-end (projection -> pixel -> Compose touch) without error.
 *  3. The auto-download gate holds: in test mode CountryPreloadMiddleware
 *     no-ops, so injected overlay data is never clobbered by a real download
 *     (the previously-flaky overlay-render race).
 */
@RunWith(AndroidJUnit4::class)
class Phase1HarnessTest : MapVisualTest() {

    private val centerLat = 40.0150
    private val centerLon = -105.2705

    @Test
    fun projectionHookResolvesGeoPointsToScreen() {
        scenario("projectionHookResolvesGeoPointsToScreen") {
            story("The instrumented harness can convert a geographic point to a screen pixel via the live MapLibre projection.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                given("the map is composed and centred at Boulder") {
                    CacheManager.initialize(activity.applicationContext)
                    MapTestHelper.grantLocationPermissions()
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                    store.dispatch(MapAction.UpdateCenter(GeoPoint(centerLat, centerLon)))
                    store.dispatch(MapAction.UpdateZoom(13.0))
                    composeTestRule.waitForIdle()
                }

                then("the projection resolves geo points to correctly-oriented screen pixels") {
                    val center = MapTestHelper.screenPxForGeoPoint(composeTestRule, centerLat, centerLon)
                    val east = MapTestHelper.screenPxForGeoPoint(composeTestRule, centerLat, centerLon + 0.01)
                    val north = MapTestHelper.screenPxForGeoPoint(composeTestRule, centerLat + 0.01, centerLon)

                    // East of centre projects further right; north projects up (smaller y).
                    assertThat(east.x).isGreaterThan(center.x)
                    assertThat(north.y).isLessThan(center.y)
                }
            }
        }
    }

    @Test
    fun gestureHelperDrivesTouchAtGeoPoint() {
        scenario("gestureHelperDrivesTouchAtGeoPoint") {
            story("A gesture helper can tap the map at a geographic coordinate end-to-end.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                given("the map is composed and centred at Boulder") {
                    CacheManager.initialize(activity.applicationContext)
                    MapTestHelper.grantLocationPermissions()
                    composeTestRule.onNodeWithTag("map_view").assertExists()
                    store.dispatch(MapAction.UpdateCenter(GeoPoint(centerLat, centerLon)))
                    store.dispatch(MapAction.UpdateZoom(13.0))
                    composeTestRule.waitForIdle()
                }

                then("clickGeoPoint resolves the projection and dispatches a real touch without error") {
                    MapTestHelper.clickGeoPoint(composeTestRule, centerLat, centerLon)
                    composeTestRule.waitForIdle()
                }
            }
        }
    }

    @Test
    fun autoDownloadSuppressedInTestMode() {
        scenario("autoDownloadSuppressedInTestMode") {
            story("In test mode the country-preload middleware no-ops, so injected overlay data is never clobbered by a real download.") {
                val activity = composeTestRule.activity as TernParaglidingActivity
                val store = ViewModelProvider(activity)[MapStore::class.java]

                given("a test country code is pinned (test mode active)") {
                    CacheManager.initialize(activity.applicationContext)
                    CountryUtils.setTestCountryCode("TEST")
                }

                then("processing a map-centre change registers no country-loaded listener (the download path is gated)") {
                    val middleware = CountryPreloadMiddleware(activity.applicationContext)
                    val before = CacheManager.countryCacheManager.onCountryLoadedListeners.size
                    runBlocking {
                        middleware.process(MapAction.UpdateCenter(GeoPoint(40.0, -105.0)), store)
                    }
                    val after = CacheManager.countryCacheManager.onCountryLoadedListeners.size
                    // The listener is only registered AFTER the isTestMode() gate,
                    // so an unchanged count proves the middleware bailed early.
                    assertThat(after).isEqualTo(before)
                }
            }
        }
    }
}
