package net.osmand.plus.routing

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.CancellationSignal
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.OperationCanceledException
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.osmand.Location
import net.osmand.plus.plugins.wear.protocol.MapProtocol.DENIED
import net.osmand.plus.plugins.wear.protocol.MapProtocol.NO_MAP
import net.osmand.plus.plugins.wear.protocol.MapProtocol.OK
import net.osmand.data.LatLon
import net.osmand.data.RotatedTileBox
import net.osmand.ResultMatcher
import net.osmand.binary.BinaryMapDataObject
import net.osmand.binary.BinaryMapIndexReader
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.wear.MapPreviewRenderer
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.wear.WearOsPlugin
import net.osmand.plus.plugins.wear.WearWidgetSnapshot
import net.osmand.plus.plugins.wear.WearPreview
import net.osmand.plus.plugins.wear.protocol.MapRequest
import net.osmand.plus.plugins.wear.protocol.MapView
import net.osmand.plus.render.MapRenderRepositories
import net.osmand.plus.render.OffscreenRenderOptions
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.enums.DayNightMode
import net.osmand.plus.track.GpxSelectionParams
import net.osmand.router.RouteCalculationProgress
import net.osmand.shared.gpx.GpxFile
import net.osmand.shared.gpx.primitives.Track
import net.osmand.shared.gpx.primitives.TrkSegment
import net.osmand.shared.gpx.primitives.WptPt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Explicit fresh-emulator probe. Never run on a physical device or personal app data. */
@RunWith(AndroidJUnit4::class)
class MapPreviewRendererTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private lateinit var app: OsmandApplication

	@Test(timeout = 90_000)
	fun movingPositionCourseVersionFour() {
		prepareApp()
		val provider = app.locationProvider
		val location = provider.javaClass.getDeclaredField("location").apply { isAccessible = true }
		val fixed = provider.javaClass.getDeclaredField("lastTimeLocationFixed").apply { isAccessible = true }
		val original = location.get(provider); val originalTime = fixed.getLong(provider)
		val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java)); val enabled = plugin.isEnabled
		val before = phoneState()
		fun inject(speed: Float? = 1.4f, bearing: Float? = 90f, age: Long = 0) = onMain {
			fixed.setLong(provider, System.currentTimeMillis())
			location.set(provider, Location("synthetic-course").apply {
				latitude = 47.142; longitude = 9.5205; time = System.currentTimeMillis() - age
				if (speed != null) this.speed = speed
				if (bearing != null) this.bearing = bearing
			})
		}
		fun course() = MapPreviewRenderer.snapshot(app, MapRequest(UUID.randomUUID(), MapView(47.142, 9.5205, 15, 400, 400), false), CancellationSignal()).position?.bearing
		try {
			for (bearing in listOf(0f, 90f, 180f, 270f)) { inject(bearing = bearing); assertEquals(bearing, course()) }
			for (speed in listOf(null, 0f, 0.49f, Float.NaN, Float.POSITIVE_INFINITY)) { inject(speed = speed); assertNull(course()) }
			inject(speed = 0.5f); assertEquals(90f, course())
			inject(bearing = null); assertNull(course())
			inject(bearing = Float.NaN); assertNull(course())
			inject(age = 31_000); assertNull(course())
			inject()
			onMain { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)) }
			val request = MapRequest(java.util.UUID.randomUUID(), MapView(47.142, 9.5205, 15, 400, 400), false)
			WearPreview(app).use { preview ->
				val frame = preview.read(request)
				assertEquals(OK, frame.reply.status)
				assertEquals(90f, frame.reply.positionBearing)
				assertNotNull(frame.image)
			}
			assertEquals(before, phoneState())
		} finally { onMain { location.set(provider, original); fixed.setLong(provider, originalTime); assertTrue(PluginsHelper.enablePlugin(null, app, plugin, enabled)) } }
	}

	@Test(timeout = 90_000)
	fun previewShieldSizeMatchesTextScale() {
		prepareApp()
		val before = phoneState()
		val token = CancellationSignal()
		val snapshot = MapPreviewRenderer.snapshot(app, MapRequest(UUID.randomUUID(), MapView(47.144, 9.523, 16, 400, 400), false), token)
		val renderer = MapRenderRepositories(app, OffscreenRenderOptions(token, snapshot.style))
		try {
			for (file in snapshot.files) {
				val stream = RandomAccessFile(file, "r")
				renderer.initializeNewResource(file, BinaryMapIndexReader(stream, file))
			}
			renderer.loadMap(snapshot.box, app.resourceManager.mapTileDownloader)
			val rc = checkNotNull(renderer.visibleRenderingContext)
			val textRenderer = net.osmand.plus.render.TextRenderer(app)
			val text = net.osmand.plus.render.TextDrawInfo("95").apply { centerX = 128f; centerY = 128f }
			val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
			try {
				for (scale in listOf(1f, 1.5f)) {
					rc.textScale = scale
					textRenderer.paintText.textSize = 13f * scale
					for (shield in listOf("blue_usishield_2_road_shield", "white_usshield_2_road_shield")) {
						image.eraseColor(Color.TRANSPARENT)
						textRenderer.drawShieldIcon(rc, android.graphics.Canvas(image), text, shield)
						val icon = checkNotNull(net.osmand.plus.render.RenderingIcons.getDrawableIcon(app, shield, true))
						assertEquals("Preview shield must use its 25px style asset, scaled with text ($shield, $scale)",
							25f * scale, icon.bounds.height().toFloat(), 1f)
						assertTrue("Shield pixels missing", pixels(image).any { Color.alpha(it) != 0 })
						assertEquals(13f * scale, textRenderer.paintText.textSize, 0f)
						android.graphics.Canvas(image).drawText("95", text.centerX, text.centerY, textRenderer.paintText)
						save(image, "shield-$scale-$shield.png")
					}
				}
				val widget = net.osmand.plus.render.OsmandRenderer.RenderingContext(app)
				textRenderer.drawShieldIcon(widget, android.graphics.Canvas(image), text, "blue_usishield_2_road_shield")
				val icon = checkNotNull(net.osmand.plus.render.RenderingIcons.getDrawableIcon(app, "blue_usishield_2_road_shield", true))
				assertEquals("Phone widget keeps its 40dp shield", net.osmand.plus.utils.AndroidUtils.dpToPx(app, 40f), icon.bounds.height())
			} finally { image.recycle() }
			save(checkNotNull(renderer.bitmap), "shield-public-map.png")
			assertEquals(before, phoneState())
		} finally { renderer.close() }
	}

	@Test(timeout = 90_000)
	fun wearPluginAccess() {
		prepareApp()
		val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java))
		val enabled = plugin.isEnabled
		val before = phoneState()
		fun read() = WearPreview(app).use { preview ->
			preview.read(MapRequest(java.util.UUID.randomUUID(), MapView(47.142, 9.5205, 15, 400, 400), false))
		}
		try {
			onMain { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, false)) }
			read().let { assertEquals(DENIED, it.reply.status); assertNull(it.image) }
			onMain { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)) }
			read().let { frame ->
				assertEquals(OK, frame.reply.status)
				assertNotNull(frame.reply.base)
				assertNotNull(net.osmand.plus.plugins.wear.protocol.MapProtocol.layerImages(checkNotNull(frame.image)))
			}
			assertEquals(before, phoneState())
			onMain { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, false)) }
			read().let { assertEquals(DENIED, it.reply.status); assertNull(it.image) }
		} finally { onMain { PluginsHelper.enablePlugin(null, app, plugin, enabled) } }
	}

	@Test
	fun remainingRouteStartClamps() {
		assertEquals(0, MapPreviewRenderer.remainingRouteStart(0, 0))
		assertEquals(0, MapPreviewRenderer.remainingRouteStart(0, 10))
		assertEquals(4, MapPreviewRenderer.remainingRouteStart(4, 10))
		assertEquals(10, MapPreviewRenderer.remainingRouteStart(12, 10))
	}

	@Test(timeout = 90_000)
	fun unchangedOverlayIsReused() {
		prepareApp()
		val plugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java))
		val enabled = plugin.isEnabled
		val view = MapView(47.142, 9.5205, 15, 400, 400)
		fun snapshot(at: MapView) = MapPreviewRenderer.snapshot(app, MapRequest(UUID.randomUUID(), at, false), CancellationSignal())
		try {
			onMain { assertTrue(PluginsHelper.enablePlugin(null, app, plugin, true)) }
			MapPreviewRenderer.clearOverlayCache()
			assertNull("Nothing cached yet", snapshot(view).cachedOverlay)
			val first = WearPreview(app).use { it.read(MapRequest(UUID.randomUUID(), view, false)) }
			assertEquals(OK, first.reply.status)
			val again = snapshot(view)
			val cached = checkNotNull(again.cachedOverlay) { "The same view must reuse the compressed overlay" }
			assertTrue("A reused overlay copies no points", again.lines.isEmpty())
			val firstLayers = checkNotNull(net.osmand.plus.plugins.wear.protocol.MapProtocol.layerImages(checkNotNull(first.image)))
			assertArrayEquals(firstLayers.second, cached.bytes)
			assertEquals(first.reply.overlay, cached.revision)
			// A watch that holds no overlay revision still gets the full overlay.
			val second = WearPreview(app).use { it.read(MapRequest(UUID.randomUUID(), view, false)) }
			assertEquals(OK, second.reply.status)
			assertNotNull(checkNotNull(net.osmand.plus.plugins.wear.protocol.MapProtocol.layerImages(checkNotNull(second.image))).second)
			// A watch holding this revision and base gets neither layer: only the 12-byte container.
			val unchanged = WearPreview(app).use {
				it.read(MapRequest(UUID.randomUUID(), view, false, cachedBase = second.reply.base, cachedOverlay = second.reply.overlay))
			}
			assertEquals(OK, unchanged.reply.status)
			assertEquals(second.reply.overlay, unchanged.reply.overlay)
			assertEquals(12, checkNotNull(unchanged.image).size)
			// A stale revision gets the overlay again.
			val stale = WearPreview(app).use {
				it.read(MapRequest(UUID.randomUUID(), view, false, cachedBase = second.reply.base, cachedOverlay = 42L))
			}
			assertNotNull(checkNotNull(net.osmand.plus.plugins.wear.protocol.MapProtocol.layerImages(checkNotNull(stale.image))).second)
			assertNull("Another view must draw its own overlay", snapshot(view.copy(longitude = 9.53)).cachedOverlay)
		} finally {
			MapPreviewRenderer.clearOverlayCache()
			onMain { PluginsHelper.enablePlugin(null, app, plugin, enabled) }
		}
	}

	@Test(timeout = 60_000)
	fun previewSkipsMapsHiddenOnThePhone() {
		prepareApp()
		val map = app.resourceManager.indexFiles.keys.first { it.startsWith("Liechtenstein") && it.endsWith(".obf") }
		val phoneRenderer = app.resourceManager.renderer
		fun previewFiles() = MapPreviewRenderer.snapshot(app,
			MapRequest(UUID.randomUUID(), MapView(47.142, 9.5205, 15, 400, 400), false), CancellationSignal())
			.files.map { it.name }
		assertTrue("Visible map must be used", map in previewFiles())
		try {
			// The same mechanism hides travel guides when they are turned off on the phone.
			onMain { phoneRenderer.addHiddenFileName(map) }
			assertFalse("A map hidden on the phone must not draw on the watch", map in previewFiles())
		} finally {
			onMain { phoneRenderer.removeHiddenFileName(map) }
		}
		assertTrue("Showing the map again restores it", map in previewFiles())
	}

	@Test
	fun followDeadZoneIsAFewPreviewPixels() {
		val lat = 47.142; val lon = 9.5205; val zoom = 16
		// One preview pixel at zoom 16, in degrees of longitude.
		val pixel = 360.0 / (256.0 * Math.pow(2.0, zoom.toDouble()))
		assertTrue(MapPreviewRenderer.withinFollowDeadZone(lat, lon, lat, lon, zoom))
		assertTrue("3 px of GPS noise keeps the map still",
			MapPreviewRenderer.withinFollowDeadZone(lat, lon, lat, lon + 3 * pixel, zoom))
		assertFalse("5 px of movement recentres the map",
			MapPreviewRenderer.withinFollowDeadZone(lat, lon, lat, lon + 5 * pixel, zoom))
		assertFalse("The same distance is more pixels after zooming in",
			MapPreviewRenderer.withinFollowDeadZone(lat, lon, lat, lon + 3 * pixel, zoom + 1))
		assertTrue("The antimeridian is not a jump",
			MapPreviewRenderer.withinFollowDeadZone(0.0, 179.99999, 0.0, -179.99999, zoom))
	}

	@Test
	fun oversizedOverlaysAreThinnedNotFailed() {
		val budget = MapPreviewRenderer.MAX_OVERLAY_POINTS
		assertEquals(1, MapPreviewRenderer.overlayStride(budget.toLong()))
		assertEquals(2, MapPreviewRenderer.overlayStride(budget + 1L))
		assertEquals(5, MapPreviewRenderer.overlayStride(budget * 5L))
		// Several long tracks plus a route: every line stays, and the total fits the budget.
		val sizes = listOf(350_000, 400_000, 180_000, 70_001, 1, 2)
		val stride = MapPreviewRenderer.overlayStride(sizes.sumOf { it.toLong() })
		val kept = sizes.sumOf { MapPreviewRenderer.sampledCount(it, stride) }
		assertTrue("kept=$kept", kept <= budget + sizes.size)
		assertTrue(sizes.all { MapPreviewRenderer.sampledCount(it, stride) >= 1 })
	}

	@Test
	fun thinnedLineKeepsBothEnds() {
		for ((size, stride) in listOf(1 to 3, 2 to 3, 10 to 3, 10 to 1, 9 to 4, 7 to 7)) {
			val count = MapPreviewRenderer.sampledCount(size, stride)
			val indices = (0 until count).map { MapPreviewRenderer.sampledIndex(it, size, stride) }
			assertEquals("size=$size stride=$stride", 0, indices.first())
			assertEquals("size=$size stride=$stride", size - 1, indices.last())
			assertEquals("size=$size stride=$stride", indices.distinct(), indices)
			assertEquals("size=$size stride=$stride", indices.sorted(), indices)
		}
		assertEquals(0, MapPreviewRenderer.sampledCount(0, 2))
	}

	/** The watch preview must draw only the route ahead of the current position, like the phone. */
	@Test(timeout = 120_000)
	fun remainingRouteOnly() {
		prepareApp()
		val originalRoute = app.routingHelper.route
		try {
			val params = RouteCalculationParams().apply {
				ctx = app; mode = ApplicationMode.PEDESTRIAN
				start = Location("synthetic-remaining-route").apply { latitude = 47.13969; longitude = 9.52130 }
				end = LatLon(47.14450, 9.52170); intermediates = emptyList()
				calculationProgress = RouteCalculationProgress(); initialCalculation = true
			}
			val route = app.routingHelper.provider.calculateRouteImpl(params)
			assertTrue("Public walking route did not calculate", route.isCalculated)
			val points = route.immutableAllLocations
			assertTrue("Route fixture too short", points.size > 4)
			val start = points.size / 2
			onMain {
				app.routingHelper.setRoute(route)
				route.updateCurrentRoute(start)
			}
			val snapshot = MapPreviewRenderer.snapshot(app,
				MapRequest(UUID.randomUUID(), MapView(points[start].latitude, points[start].longitude, 16, 400, 400), false),
				CancellationSignal())
			val line = snapshot.lines.lastOrNull { it.navigation }
			assertNotNull("Route line missing", line)
			assertEquals("Only the remaining route should be drawn", points.size - start, line!!.coordinates.size / 2)
			assertEquals(points[start].latitude, line.coordinates[0], 0.0)
			assertEquals(points[start].longitude, line.coordinates[1], 0.0)
		} finally {
			onMain { app.routingHelper.setRoute(originalRoute) }
		}
	}

	/** Holds only public/synthetic data while the real paired watch requests images. */
	@Test(timeout = 300_000)
	fun pairedWatchFixture() {
		check(InstrumentationRegistry.getArguments().getString("pairedWatch") == "true")
		prepareApp()
		val keyguard = app.getSystemService(KeyguardManager::class.java)
		check(!keyguard.isDeviceSecure) { "Use the disposable emulator without a saved credential" }
		val stop = File(app.cacheDir, "map-preview-paired.stop")
		check(!stop.exists()) { "Remove the old test stop marker first" }
		val wearPlugin = checkNotNull(PluginsHelper.getPlugin(WearOsPlugin::class.java))
		val wearEnabled = wearPlugin.isEnabled
		val sources = InstrumentationRegistry.getArguments().getString("pairedSources") == "true"
		val sourceCommand = File(app.cacheDir, "map-preview-paired.source")
		check(!sources || !sourceCommand.exists()) { "Old source-test command exists" }
		val alternate = fixtureGpx().apply {
			path = File(app.getAppPath(null), "preview-synthetic-alt.gpx").absolutePath
			tracks.first().setColor(Color.CYAN)
			for (point in tracks.first().segments.first().points) point.lon += 0.004
		}
		val originalRoute = app.routingHelper.route
		val gpx = fixtureGpx()
		val provider = app.locationProvider
		val location = provider.javaClass.getDeclaredField("location").apply { isAccessible = true }
		val fixed = provider.javaClass.getDeclaredField("lastTimeLocationFixed").apply { isAccessible = true }
		val originalLocation = location.get(provider)
		val originalTime = fixed.getLong(provider)
		val lockTimeout = shell("settings get secure lock_screen_lock_after_timeout").trim()
		var pinInstalled = false
		try {
			onMain { check(PluginsHelper.enablePlugin(null, app, wearPlugin, true)) }
			val params = RouteCalculationParams().apply {
				ctx = app; mode = ApplicationMode.PEDESTRIAN
				start = Location("synthetic-map-preview").apply { latitude = 47.13969; longitude = 9.52130 }
				end = LatLon(47.14450, 9.52170); intermediates = emptyList()
				calculationProgress = RouteCalculationProgress(); initialCalculation = true
			}
			val route = app.routingHelper.provider.calculateRouteImpl(params)
			assertTrue("Public walking route did not calculate", route.isCalculated)
			val routePoints = route.immutableAllLocations
			routePoints.forEachIndexed { index, point ->
				if (index == 0 || index == routePoints.lastIndex || index % 3 == 0) point.removeAltitude()
				else point.altitude = 110.0 + index % 40
			}
			val alternateRoute = if (sources) app.routingHelper.provider.calculateRouteImpl(RouteCalculationParams().apply {
				ctx = app; mode = ApplicationMode.PEDESTRIAN
				start = Location("synthetic-map-preview").apply { latitude = 47.13969; longitude = 9.52130 }
				end = LatLon(47.14450, 9.51500); intermediates = emptyList()
				calculationProgress = RouteCalculationProgress(); initialCalculation = true
			}).also {
				assertTrue("Alternate public route did not calculate", it.isCalculated)
				it.immutableAllLocations.forEachIndexed { index, point -> point.altitude = 130.0 + index % 35 }
			} else route
			onMain {
				app.routingHelper.setRoute(route)
				app.selectedGpxHelper.selectGpxFile(gpx, GpxSelectionParams.newInstance().showOnMap())
			}
			val profile = WearWidgetSnapshot.routeProfile(app, System.currentTimeMillis())
			assertNotNull("Route profile must retain valid elevation points across gaps", profile)
			assertTrue("Profile must start after an unmeasured route endpoint", profile!!.points.first().first > 0)
			assertTrue("Profile must end before an unmeasured route endpoint", profile.points.last().first < profile.totalMeters)
			instrumentation.sendStatus(0, Bundle().apply { putString("stream",
				"PROFILE_FIXTURE: samples=${profile?.points?.size ?: 0} routePoints=${route.immutableAllLocations.size}.\n") })
			shell("input keyevent 3")
			SystemClock.sleep(1_000)
			val before = phoneState()
			assertTrue(shell("locksettings set-pin $TEST_PIN").contains("set", true)); pinInstalled = true
			shell("settings put secure lock_screen_lock_after_timeout 0")
			shell("input keyevent 223")
			waitFor("secure authentication lock") { keyguard.isDeviceLocked }
			fun fix() = onMain {
				location.set(provider, Location("synthetic-preview").apply { latitude = 47.142; longitude = 9.5205; time = System.currentTimeMillis(); speed = 1.4f; bearing = 90f })
				fixed.setLong(provider, System.currentTimeMillis())
			}
			fix()
			instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PAIRED_FIXTURE_READY: public basemap, calculated route, selected GPX, fresh synthetic position; phone securely locked.\n") })
			var expected = before
			var phase = 0
			val deadline = SystemClock.elapsedRealtime() + 240_000
			while (!stop.exists() && SystemClock.elapsedRealtime() < deadline) {
				assertTrue("Phone must stay securely locked", keyguard.isDeviceLocked && keyguard.isKeyguardLocked)
				assertFalse("Phone screen must stay off", app.getSystemService(PowerManager::class.java).isInteractive)
				if (sources && sourceCommand.exists()) {
					val next = sourceCommand.readText().trim().toInt()
					if (next != phase) {
						check(next == phase + 1 && next in 1..8)
						assertEquals("Watch changed phone state before source edit", expected, phoneState())
						onMain {
							app.routingHelper.setRoute(when (next) { 6, 7 -> RouteCalculationResult(""); 8 -> route; else -> alternateRoute })
							app.selectedGpxHelper.selectGpxFile(gpx, GpxSelectionParams.newInstance().hideFromMap())
							app.selectedGpxHelper.selectGpxFile(alternate, GpxSelectionParams.newInstance().hideFromMap())
							if (next in listOf(1, 3, 8)) app.selectedGpxHelper.selectGpxFile(gpx, GpxSelectionParams.newInstance().showOnMap())
							if (next in listOf(2, 3, 5, 6)) app.selectedGpxHelper.selectGpxFile(alternate, GpxSelectionParams.newInstance().showOnMap())
						}
						phase = next; expected = phoneState()
						assertEquals("Source edit changed phone camera/follow", before.take(6), expected.take(6))
						instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PAIRED_SOURCE_APPLIED:$phase\n") })
					}
				}
				fix(); SystemClock.sleep(500)
			}
			assertTrue("Host did not release the paired fixture", stop.exists())
			assertEquals("Paired watch changed phone view/follow/route/selection", expected, phoneState())
			if (sources) assertEquals("Source test did not complete", 8, phase)
			instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PAIRED_PHONE_PRESERVED: secure lock and unchanged phone view/follow/route/selection.\n") })
		} finally {
			onMain { PluginsHelper.enablePlugin(null, app, wearPlugin, wearEnabled) }
			if (pinInstalled) { shell("locksettings clear --old $TEST_PIN"); shell("input keyevent 224") }
			if (lockTimeout == "null") shell("settings delete secure lock_screen_lock_after_timeout")
			else { check(lockTimeout.matches(Regex("[0-9]+"))); shell("settings put secure lock_screen_lock_after_timeout $lockTimeout") }
			onMain {
				location.set(provider, originalLocation); fixed.setLong(provider, originalTime)
				app.routingHelper.setRoute(originalRoute)
				app.selectedGpxHelper.selectGpxFile(gpx, GpxSelectionParams.newInstance().hideFromMap())
				app.selectedGpxHelper.selectGpxFile(alternate, GpxSelectionParams.newInstance().hideFromMap())
			}
			if (stop.exists()) check(stop.delete())
			if (sources && sourceCommand.exists()) check(sourceCommand.delete())
		}
	}

	@Test(timeout = 300_000)
	fun independentDetailedViewsAndLiveOverlaysUnderSecureLock() {
		prepareApp()
		val originalRoute = app.routingHelper.route
		val gpx = fixtureGpx()
		val keyguard = app.getSystemService(KeyguardManager::class.java)
		check(!keyguard.isDeviceSecure) { "Use a fresh emulator with no lock credential" }
		var pinInstalled = false
		// The watch line uses the phone's route colour; an opaque custom colour makes it countable.
		val routeMode = app.routingHelper.appMode
		val coloring = app.settings.ROUTE_COLORING_TYPE
		val customDay = app.settings.CUSTOM_ROUTE_COLOR_DAY
		val customNight = app.settings.CUSTOM_ROUTE_COLOR_NIGHT
		val savedColoring = coloring.getModeValue(routeMode)
		val savedDay = customDay.getModeValue(routeMode)
		val savedNight = customNight.getModeValue(routeMode)
		coloring.setModeValue(routeMode, net.osmand.shared.routing.ColoringType.CUSTOM_COLOR)
		customDay.setModeValue(routeMode, ROUTE_TEST_COLOR)
		customNight.setModeValue(routeMode, ROUTE_TEST_COLOR)
		try {
			val params = RouteCalculationParams().apply {
				ctx = app
				mode = ApplicationMode.PEDESTRIAN
				start = Location("synthetic-map-preview").apply { latitude = 47.13969; longitude = 9.52130 }
				end = LatLon(47.14450, 9.52170)
				intermediates = emptyList()
				calculationProgress = RouteCalculationProgress()
				initialCalculation = true
			}
			val route = app.routingHelper.provider.calculateRouteImpl(params)
			assertTrue("Offline walking route did not calculate", route.isCalculated)
			onMain {
				app.routingHelper.setRoute(route)
				app.selectedGpxHelper.selectGpxFile(gpx, GpxSelectionParams.newInstance().showOnMap())
				app.osmandMap.mapView.setLatLon(47.165, 9.508)
				app.osmandMap.mapView.setIntZoom(12)
				app.mapViewTrackingUtilities.isMapLinkedToLocation = true
			}
			shell("input keyevent 3")
			SystemClock.sleep(1_000)
			val before = phoneState()
			val baseline = render(47.142, 9.5205, 15, false)
			try {
				assertTrue("A detailed basemap is required, not an empty image", pixels(baseline).toSet().size > 32)
				save(baseline, "base.png")
			} finally { baseline.recycle() }
			val first = render(47.142, 9.5205, 15)
			val firstPixels: IntArray
			try {
				firstPixels = pixels(first)
				assertTrue("Navigation line missing", firstPixels.count { it == ROUTE_TEST_COLOR } > 20)
				assertTrue("Selected GPX line missing", firstPixels.count { it == Color.MAGENTA } > 20)
				save(first, "route-and-gpx.png")
			} finally { first.recycle() }
			val panned = render(47.144, 9.523, 16)
			try {
				assertFalse("Pan/zoom must obtain a different rendered view", firstPixels.contentEquals(pixels(panned)))
				save(panned, "panned.png")
			} finally { panned.recycle() }
			assertEquals("Preview changed the phone view/follow/route/selection", before, phoneState())

			assertTrue(shell("locksettings set-pin $TEST_PIN").contains("set", ignoreCase = true))
			pinInstalled = true
			shell("settings put secure lock_screen_lock_after_timeout 0")
			shell("input keyevent 223")
			waitFor("secure authentication lock") { keyguard.isDeviceLocked }
			assertTrue(keyguard.isKeyguardLocked)
			assertFalse(app.getSystemService(PowerManager::class.java).isInteractive)
			val locked = render(47.142, 9.5205, 15)
			try {
				assertArrayEquals("Secure-lock preview differs from the same background view", firstPixels, pixels(locked))
				save(locked, "secure-lock.png")
			} finally { locked.recycle() }
			assertEquals(before, phoneState())

			onMain {
				app.routingHelper.setRoute(RouteCalculationResult(""))
				app.selectedGpxHelper.selectGpxFile(gpx, GpxSelectionParams.newInstance().hideFromMap())
			}
			val cleared = render(47.142, 9.5205, 15)
			try {
				val values = pixels(cleared)
				assertEquals("Old navigation line retained", 0, values.count { it == ROUTE_TEST_COLOR })
				assertEquals("Old GPX line retained", 0, values.count { it == Color.MAGENTA })
			} finally { cleared.recycle() }
			instrumentation.sendStatus(0, android.os.Bundle().apply {
				putString("stream", "Preview observed: detailed base; calculated walking route; selected GPX; pan/zoom; unchanged phone view/follow; background + secure lock; route/selection clearing.\n")
			})
		} finally {
			if (pinInstalled) {
				shell("locksettings clear --old $TEST_PIN")
				shell("input keyevent 224")
			}
			onMain {
				app.routingHelper.setRoute(originalRoute)
				app.selectedGpxHelper.selectGpxFile(gpx, GpxSelectionParams.newInstance().hideFromMap())
			}
			coloring.setModeValue(routeMode, savedColoring)
			customDay.setModeValue(routeMode, savedDay)
			customNight.setModeValue(routeMode, savedNight)
		}
	}

	@Test(timeout = 180_000)
	fun realRendererCancellationAndResourceUse() {
		prepareApp()
		val before = phoneState()
		val coldStart = SystemClock.elapsedRealtimeNanos()
		val cold = render(47.142, 9.5205, 15)
		val coldMs = (SystemClock.elapsedRealtimeNanos() - coldStart) / 1_000_000
		try {
			val bytes = java.io.ByteArrayOutputStream().also { assertTrue(cold.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
			val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
			try { assertArrayEquals(pixels(cold), pixels(decoded)) } finally { decoded.recycle() }
		} finally { cold.recycle() }
		repeat(2) { render(47.142, 9.5205, 15).recycle() }
		Runtime.getRuntime().gc(); SystemClock.sleep(200)
		fun fdCount() = checkNotNull(File("/proc/self/fd").list()).size
		fun obfCount(): Int = checkNotNull(File("/proc/self/fd").list()).count {
			runCatching { android.system.Os.readlink("/proc/self/fd/$it").endsWith(".obf") }.getOrDefault(false)
		}
		val baseFd = fdCount()
		val baseObf = obfCount()
		val basePss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
		val peakFd = AtomicInteger(baseFd)
		val peakObf = AtomicInteger(baseObf)
		val baseNative = Debug.getNativeHeapAllocatedSize()
		val peakNative = AtomicLong(baseNative)
		val sampling = AtomicBoolean(true)
		val sampler = Thread({
			while (sampling.get()) {
				peakFd.accumulateAndGet(fdCount()) { a, b -> maxOf(a, b) }
				peakObf.accumulateAndGet(obfCount()) { a, b -> maxOf(a, b) }
				peakNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { a, b -> maxOf(a, b) }
				SystemClock.sleep(5)
			}
		}, "PreviewResourceSampler").apply { start() }
		val wall = mutableListOf<Long>()
		val encoding = mutableListOf<Long>()
		val cpu = mutableListOf<Long>()
		try {
			repeat(12) { index ->
				val size = if (index % 2 == 0) 400 else 512
				val started = SystemClock.elapsedRealtimeNanos()
				val cpuStarted = Debug.threadCpuTimeNanos()
				val image = render(47.142 + (index % 4) * 0.0002, 9.5205, 15 + index % 2, size = size)
				val rendered = SystemClock.elapsedRealtimeNanos()
				try {
					val bytes = java.io.ByteArrayOutputStream().also { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
					assertTrue(bytes.size in 1..net.osmand.plus.plugins.wear.protocol.MapProtocol.MAX_BYTES)
				} finally { image.recycle() }
				wall.add((rendered - started) / 1_000_000)
				encoding.add((SystemClock.elapsedRealtimeNanos() - rendered) / 1_000_000)
				cpu.add((Debug.threadCpuTimeNanos() - cpuStarted) / 1_000_000)
			}
		} finally { sampling.set(false); sampler.join(2_000) }
		assertFalse(sampler.isAlive)
		Runtime.getRuntime().gc(); SystemClock.sleep(200)
		val finalFd = fdCount()
		val finalObf = obfCount()
		val finalPss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
		assertTrue("File descriptor growth", finalFd <= baseFd + 2)
		assertEquals("Private OBF reader retained", baseObf, finalObf)
		val cancelled = CancellationSignal().apply { cancel() }
		try {
			render(47.142, 9.5205, 15, cancellation = cancelled).recycle()
			fail("Pre-cancelled request returned an image")
		} catch (_: OperationCanceledException) { }
		for (invalid in listOf(Double.NaN, 86.0)) {
			try { render(invalid, 9.52, 15).recycle(); fail("Invalid latitude accepted") }
			catch (_: IllegalArgumentException) { }
		}
		try { render(0.0, 0.0, 15).recycle(); fail("Missing map accepted") }
		catch (error: MapPreviewRenderer.Failure) { assertEquals(NO_MAP, error.status) }
		val searchCancelMs = cancelDuring("search")
		val drawCancelMs = cancelDuring("draw")
		assertEquals(before, phoneState())
		assertTrue(fdCount() <= baseFd + 2)
		assertEquals(baseObf, obfCount())
		fun summary(values: List<Long>): String {
			val sorted = values.sorted()
			return "median=${(sorted[5] + sorted[6]) / 2} p95=${sorted[11]} max=${sorted.last()}"
		}
		instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
			"Real renderer: 12 frames, 400/512 px, zoom 15/16; firstResourceFrame=${coldMs}ms; render ${summary(wall)}ms; encode ${summary(encoding)}ms; threadCPU ${summary(cpu)}ms; FD base=$baseFd peak=${peakFd.get()} final=$finalFd; OBF base=$baseObf peak=${peakObf.get()} final=$finalObf; native heap base=$baseNative peak=${peakNative.get()}; PSS kB before=$basePss after=$finalPss; search cancel=${searchCancelMs}ms draw cancel=${drawCancelMs}ms; direct PNG pixel hash matched; input/missing-map checks and phone-state preservation passed.\n") })
	}

	private fun prepareApp() {
		check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
		check(Build.HARDWARE == "ranchu" && instrumentation.targetContext.packageName == "net.osmand.dev")
		app = instrumentation.targetContext.applicationContext as OsmandApplication
		waitFor("application initialization") { !app.isApplicationInitializing }
		check(app.selectedGpxHelper.selectedGPXFiles.isEmpty()) { "Use a fresh emulator" }
		check(app.routingHelper.currentCalculatedRoute.isEmpty()) { "Use a fresh emulator" }
		app.resourceManager.reloadIndexes(null, mutableListOf())
		check(app.resourceManager.indexFiles.keys.any { it.startsWith("Liechtenstein") }) { "Public map fixture missing" }
	}

	/**
	 * Cancels an offscreen render once [phase] ("search" or "draw") is under way and returns the
	 * cancellation latency. Core exposes no progress, so the phases are observed from outside: the map
	 * search is running once the search matcher publishes, and drawing once the bitmap exists. An
	 * uncancelled run of the same view shows that the cancelled one stopped inside the phase.
	 */
	private fun cancelDuring(phase: String): Long {
		val full = offscreenRun(phase, cancel = false)
		val cancelled = offscreenRun(phase, cancel = true)
		when (phase) {
			"search" -> assertTrue("Cancellation was only noticed after the search: ${cancelled.published} of ${full.published} objects",
				cancelled.published < full.published)
			"draw" -> assertTrue("Cancellation was only noticed after drawing: ${cancelled.ms} ms, full draw ${full.ms} ms",
				cancelled.ms < full.ms)
		}
		return cancelled.ms
	}

	private class OffscreenRun(val ms: Long, val published: Int)

	/** Milliseconds from [phase] being observed (and cancelled, if [cancel]) to the end of the render. */
	private fun offscreenRun(phase: String, cancel: Boolean): OffscreenRun {
		var style: OffscreenRenderOptions.Style? = null
		onMain { style = app.resourceManager.renderer.captureOffscreenStyle(15, 1f) }
		val published = AtomicInteger()
		val probe = object : ResultMatcher<BinaryMapDataObject> {
			override fun publish(item: BinaryMapDataObject) = true.also { published.incrementAndGet() }
			override fun isCancelled() = false
		}
		val token = CancellationSignal()
		val renderer = MapRenderRepositories(app, OffscreenRenderOptions(token, checkNotNull(style), probe, true))
		val executor = Executors.newSingleThreadExecutor()
		val task = executor.submit<Unit> {
			try {
				for (file in app.resourceManager.indexFiles.values) {
					if (!file.name.endsWith(".obf") || !file.isFile) continue
					val stream = RandomAccessFile(file, "r")
					try { renderer.initializeNewResource(file, BinaryMapIndexReader(stream, file)) }
					catch (error: Exception) { stream.close(); throw error }
				}
				val box = RotatedTileBox.RotatedTileBoxBuilder().setLocation(47.142, 9.5205).setZoom(15)
					.density(1f).setMapDensity(1.0).setPixelDimensions(512, 512, 0.5f, 0.5f).build()
				renderer.loadMap(box, app.resourceManager.mapTileDownloader)
				token.throwIfCanceled()
			} finally { renderer.close() }
		}
		try {
			val started = { if (phase == "search") published.get() > 0 else renderer.bitmap != null }
			val deadline = SystemClock.elapsedRealtime() + 10_000
			while (!started() && !task.isDone && SystemClock.elapsedRealtime() < deadline) Thread.yield()
			assertTrue("Did not observe real $phase work", started() && !task.isDone)
			val from = SystemClock.elapsedRealtimeNanos()
			if (cancel) {
				token.cancel()
				try { task.get(5, TimeUnit.SECONDS); fail("Cancellation did not reach the worker") }
				catch (error: ExecutionException) { assertTrue(error.cause is OperationCanceledException) }
			} else {
				task.get(30, TimeUnit.SECONDS)
			}
			return OffscreenRun((SystemClock.elapsedRealtimeNanos() - from) / 1_000_000, published.get())
		} finally { token.cancel(); executor.shutdown(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
	}

	private fun render(lat: Double, lon: Double, zoom: Int, overlays: Boolean = true,
		size: Int = 400, cancellation: CancellationSignal = CancellationSignal()): Bitmap {
		val request = MapRequest(UUID.randomUUID(), MapView(lat, lon, zoom, size, size), false, size, size)
		return MapPreviewRenderer.render(app, MapPreviewRenderer.snapshot(app, request, cancellation, overlays), cancellation, overlays)
	}

	private fun phoneState(): List<Any> {
		var state: List<Any>? = null
		onMain {
			val box = app.osmandMap.mapView.currentRotatedTileBox
			state = listOf(box.latitude, box.longitude, box.zoom, box.zoomFloatPart, box.rotate,
				app.mapViewTrackingUtilities.isMapLinkedToLocation,
				app.routingHelper.route,
				app.selectedGpxHelper.selectedGPXFiles.map { it.gpxFile.path })
		}
		return state!!
	}

	@Test
	fun unrelatedInstalledMapsDoNotTripThePreviewLimit() {
		val files = (0 until 20).map { File("installed-$it.obf") }
		val selected = MapPreviewRenderer.selectBounded(files) { it.name == "installed-0.obf" }
		assertEquals(listOf(files[0]), selected)
	}

	@Test
	fun liveUpdateFilesForOneViewportFitThePreviewLimit() {
		// A region map, its contour/depth files and a month of OsmAnd Live daily updates.
		val files = (0 until 40).map { File("relevant-$it.obf") }
		assertEquals(files, MapPreviewRenderer.selectBounded(files) { true })
	}

	@Test(expected = MapPreviewRenderer.Failure::class)
	fun genuinelyRelevantFilesStillHaveABudget() {
		val files = (0..MapPreviewRenderer.MAX_PREVIEW_FILES).map { File("relevant-$it.obf") }
		MapPreviewRenderer.selectBounded(files) { true }
	}

	private fun fixtureGpx() = GpxFile("Public synthetic preview fixture").apply {
		path = File(app.getAppPath(null), "preview-synthetic.gpx").absolutePath
		tracks.add(Track().apply {
			setColor(Color.MAGENTA)
			segments.add(TrkSegment().apply {
				for ((lat, lon) in listOf(47.1400 to 9.5180, 47.1410 to 9.5175, 47.1430 to 9.5190)) {
					points.add(WptPt().apply { this.lat = lat; this.lon = lon })
				}
			})
		})
	}

	private fun pixels(image: Bitmap) = IntArray(image.width * image.height).also {
		image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
	}

	private fun save(image: Bitmap, name: String) {
		val directory = File(app.externalCacheDir, "map-preview-prototype").apply { mkdirs() }
		File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
	}

	private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
		.use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }

	private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

	private fun waitFor(description: String, condition: () -> Boolean) {
		val deadline = SystemClock.elapsedRealtime() + 60_000
		while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
		assertTrue("Timed out: $description", condition())
	}

	companion object {
		// Disposable emulator credential, never used on a physical device.
		private const val TEST_PIN = "698241"
		/** Opaque, and unlike any map-style colour, so route pixels can be counted. */
		private const val ROUTE_TEST_COLOR = -38400 // #ff6a00
	}
}
