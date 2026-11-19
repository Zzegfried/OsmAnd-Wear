package net.osmand.plus.render;

import android.os.CancellationSignal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.render.RenderingRuleSearchRequest;
import net.osmand.render.RenderingRulesStorage;

/**
 * Settings for an isolated, cancellable offscreen render: a {@link MapRenderRepositories} instance that
 * draws one image away from the main map, for example a preview for another device or an image export.
 * <p>
 * Such an instance has its own readers, bitmap and cache. It uses the Java renderer, never the global
 * native library, registers no application listeners, shows no toasts and rethrows render failures to
 * its caller. It renders with the {@link Style} captured here, so it never reads or changes the main
 * map's style state. Cancelling {@link #cancellation} stops the map search and the drawing loop.
 */
public final class OffscreenRenderOptions {

	@NonNull
	public final CancellationSignal cancellation;
	@NonNull
	public final Style style;
	/** Optional caller limits on the map search; it is always also stopped by {@link #cancellation}. */
	@Nullable
	public final ResultMatcher<BinaryMapDataObject> searchMatcher;
	/**
	 * True draws road shields at the map style's asset size instead of the phone's 40 dp widget size,
	 * so their size follows only the image and text scale.
	 */
	public final boolean styleSizedShields;

	public OffscreenRenderOptions(@NonNull CancellationSignal cancellation, @NonNull Style style) {
		this(cancellation, style, null, true);
	}

	public OffscreenRenderOptions(@NonNull CancellationSignal cancellation, @NonNull Style style,
	                              @Nullable ResultMatcher<BinaryMapDataObject> searchMatcher, boolean styleSizedShields) {
		this.cancellation = cancellation;
		this.style = style;
		this.searchMatcher = searchMatcher;
		this.styleSizedShields = styleSizedShields;
	}

	/**
	 * A snapshot of the rendering style, captured on the UI thread with
	 * {@link MapRenderRepositories#captureOffscreenStyle}. Each render owns its search request.
	 */
	public static final class Style {

		@NonNull
		public final RenderingRuleSearchRequest request;
		@NonNull
		public final RenderingRulesStorage storage;
		public final boolean night;
		public final String locale;
		public final float textScale;

		public Style(@NonNull RenderingRuleSearchRequest request, @NonNull RenderingRulesStorage storage,
		             boolean night, String locale, float textScale) {
			this.request = request;
			this.storage = storage;
			this.night = night;
			this.locale = locale;
			this.textScale = textScale;
		}
	}
}
