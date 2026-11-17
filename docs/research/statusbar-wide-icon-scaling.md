# Status bar and shade scaling of a non-square bitmap icon

Branches: `android16-release` and `android11-release` (API 30), `platform/frameworks/base`. Line numbers are the `?format=TEXT` files on those branch heads.

The status-bar **notification** icon is `CENTER_INSIDE` into the slot, then `canvas.scale(mIconScale)`. It is not the constructor's `CENTER`. A 2:1 bitmap whose long side is at least the slot is drawn at **15 dp × 7.5 dp** (ambient/AOD: 18 dp × 9 dp). A bitmap smaller than the slot on both sides is not enlarged by `CENTER_INSIDE`.

Primary line anchors: [android16 notification CENTER_INSIDE](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SystemUI/src/com/android/systemui/statusbar/notification/icon/IconManager.kt#161), [android11 same route](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/packages/SystemUI/src/com/android/systemui/statusbar/notification/icon/IconManager.kt#91), [android16 fixed slot width](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SystemUI/src/com/android/systemui/statusbar/notification/icon/ui/viewbinder/NotificationIconContainerViewBinder.kt#188), [android11 slot width](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/packages/SystemUI/src/com/android/systemui/statusbar/phone/NotificationIconAreaController.java#190), [android16 canvas scale](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/SystemUI/src/com/android/systemui/statusbar/StatusBarIconView.java#224), [android11 canvas scale](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/packages/SystemUI/src/com/android/systemui/statusbar/StatusBarIconView.java#210), [android16 shade icon](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/res/layout/notification_template_header.xml#44), [android11 shade icon](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/res/res/layout/notification_template_header.xml#33).

## Notification route

`IconManager` overrides the scale type after `IconBuilder.createIconView`:

- android16 `packages/SystemUI/src/com/android/systemui/statusbar/notification/icon/IconManager.kt:145-146` (`createSbIconView`), `:162-163` (status bar), `:166-167` (chip), `:173-174` (shelf), `:178-179` (AOD).
- android11 same file `:92-93`, `:96-97`, `:106-107`, and `:113` for the media centered icon.

Each assignment is `scaleType = ImageView.ScaleType.CENTER_INSIDE`.

`toStatusBarIcon` on android16 (`IconManager.kt:365-374`) uses the `StatusBarIcon` constructor that omits `Shape`, which stores `Shape.WRAP_CONTENT` (`core/java/com/android/internal/statusbar/StatusBarIcon.java:100-103`). android11 builds the same way (`IconManager.kt:221-227`) on a `StatusBarIcon` with no `Shape` field. `StatusBarIconView.set` switches to `FIT_CENTER` only when `ModesUiIcons.isEnabled()` and `shape == FIXED_SPACE` (android16 `StatusBarIconView.java:415-416`). The notification descriptor is not `FIXED_SPACE`. `set()` does not assign `CENTER_INSIDE`; the `IconManager` lines above do, and they are the notification path.

The constructor still calls `setScaleType(CENTER)` (android16 `StatusBarIconView.java:196`, android11 `:179`). `IconManager` replaces it.

## Slot

Notification `LayoutParams` are a positive square plus zero horizontal padding:

- android16 `NotificationIconContainerViewBinder.kt:188-197`: width = px(`status_bar_icon_size_sp`) + 2 × px(`status_bar_icon_horizontal_margin`). The margin is `0sp` (`packages/SystemUI/res/values/dimens.xml:443`). Height is the status-bar height.
- android11 `NotificationIconAreaController.java:190-198`: width = px(`status_bar_icon_size`) + 2 × px(`status_bar_icon_padding`). Padding is `0dp` (`dimens.xml:286`). Height is `getHeight()`.

So `lp.width` is px(22sp) on android16 and px(22dip) on android11. Both are `> 0`.

| Dimen | Value | File |
|---|---|---|
| `status_bar_icon_size` | `22dip` | framework `dimens.xml:102` (16), `:77` (11) |
| `status_bar_icon_size_sp` | `22sp` | framework `dimens.xml:104` (16 only) |
| `status_bar_icon_drawing_size` | `15dp` | SystemUI `dimens.xml:424` (16), `:272` (11) |
| `status_bar_icon_drawing_size_dark` | alias of `notification_header_icon_size_ambient` = `18dp` | SystemUI dimens `:427-428` (16), `:275-276` (11); framework `:399` (16), `:268` (11) |
| `status_bar_icon_scale_factor` | `1.0` | SystemUI dimens `:243` (16), `:106` (11) |

## CENTER_INSIDE, then the canvas

`ImageView.configureBounds` for `CENTER_INSIDE` (android16 `ImageView.java:1338-1354`):

```
if intrinsicWidth <= viewWidth && intrinsicHeight <= viewHeight: scale = 1
else: scale = min(viewWidth / intrinsicWidth, viewHeight / intrinsicHeight)
dx = round((viewWidth - intrinsicWidth * scale) * 0.5)
dy = round((viewHeight - intrinsicHeight * scale) * 0.5)
matrix: scale(scale, scale) then postTranslate(dx, dy)
```

`viewWidth` / `viewHeight` are the view minus padding (`:1289-1290`). This **downscales** when either intrinsic side exceeds the view. It does **not** upscale. `CENTER` (`:1316-1320`) only translates; that is not this route. `FIT_CENTER` (`:1356-1361`, `ScaleToFit.CENTER`) can upscale. `CENTER_INSIDE` is the one that forbids it.

`onDraw` then scales about the integer view center by `mIconScale * mIconAppearAmount` on both axes (android16 `StatusBarIconView.java:556-567`, android11 `:450-455`). `mIconAppearAmount` defaults to 1.

### android16 `mIconScale`

`maybeUpdateIconScaleDimens` (`:211-221`) uses `updateIconScaleForNonSystemIcons` when `mNotification != null`. Formula (`:224-268`), with `lp.width > 0` (true here):

```
scaleToFitIconView = min(lp.width / intrinsicWidth, lp.height / intrinsicHeight)
if scaleToFitIconView > 1: scaleToFitIconView = 1
scaledW = intrinsicWidth * scaleToFitIconView
scaledH = intrinsicHeight * scaleToFitIconView
scaleToOriginal = min(mOriginal / scaledW, mOriginal / scaledH)
if scaleToOriginal > 1:
    scaleToOriginal = min(scaleToOriginal, 1 / scaleToFitIconView)   # b/296026932
mIconScale = scaleToOriginal * (imageBounds / mOriginal) * (mNew / mOriginal)
```

`mOriginal` = px(`22dip`), `mNew` = px(`22sp`), `imageBounds` = px(`15dp`), or px(`18dp`) when `mIncreasedSize` (`reloadDimens` `:335-345`). AOD sets `setIncreasedSize(true)` (`IconManager.kt:180`). At density 1 and font scale 1, `mNew / mOriginal` is 1 and the tail is `15/22` (or `18/22`).

`scaleToFitIconView` is not itself the canvas scale. With `CENTER_INSIDE` it matches the ImageView fit: anything that does not fit the slot is reduced so its long side is the slot, and `scaleToOriginal` is then 1, so the canvas multiplies by `15/22`.

| Intrinsic vs 22 px slot | `CENTER_INSIDE` | `scaleToOriginal` | Drawn (density 1, font scale 1, not AOD) |
|---|---|---|---|
| 44×22, 30×15, 200×100, any 2:1 with a side ≥ 22 | long side = 22 | 1 | **15 × 7.5** |
| 22×11 | no scale (fits) | 1 | **15 × 7.5** |
| 16×8, both sides < 22 | no scale | clamped to 1 | **10.91 × 5.45** (not enlarged) |
| 192×192 square | fit to 22×22 | 1 | **15 × 15** |
| 48×48 after the 48 dp cap, at mdpi | fit to 22 | 1 | **15 × 15** |

AOD / `mIncreasedSize`: same table with 18 dp instead of 15 dp (18 × 9 for 2:1).

### android11 `mIconScale`

`updateIconScaleForNotifications` (`StatusBarIconView.java:210-215`) does not read the intrinsic size:

```
mIconScale = imageBounds / mStatusBarIconSize    # px(15dp) / px(22dip), or 18/22 if increased
```

`CENTER_INSIDE` still fits into the 22 dp-wide view first. A 2:1 whose long side ≥ 22 is fitted to 22, then × `15/22`, so **15 × 7.5**. A 192×192 square is fitted to the view's limiting side (the 22 dp width, because the layout height is the bar height, which is larger), then × `15/22`, so **15 × 15**. Both sides already under 22: `CENTER_INSIDE` scale is 1, canvas × `15/22` only (16×8 → 10.91×5.45). There is no `b/296026932` clamp on this branch because there is no `scaleToOriginal`.

## What is not this route

- `AnimatedImageView.setImageDrawable` (both branches, `packages/SystemUI/src/com/android/systemui/statusbar/AnimatedImageView.java`, the override that sets `mDrawableId` and calls `super.setImageDrawable` then `updateAnim`) does not scale.
- android16 `ImageView` `mMaxWidth` / `mMaxHeight` are `Integer.MAX_VALUE` (`ImageView.java:114-116`) and apply in `onMeasure` only when `adjustViewBounds` is true (default false, `:213`).
- API 30 `Builder.build` calls `reduceImageSizes` (`Notification.java:6031`). That method scales `mLargeIcon` / `largeIcon` only (`:6276-6307`). It does not call `getSmallIcon().scaleDownIfNecessary`.
- android16 `Notification.reduceImageSizes` (`:7926-7934`) and `DrawableSize.downscaleToSize` (`StatusBarIconView.getIcon`, `:485-493`; `DrawableSize.kt:100-106`) cap **bitmap pixels** to px(`notification_small_icon_size`) = px(`48dp`) (framework dimens `:930`), aspect preserved, no upscale, `TYPE_BITMAP` / `TYPE_ADAPTIVE_BITMAP` only for `reduceImageSizes`. `DrawableSize` uses `bitmap.width/height` for a `BitmapDrawable` (`DrawableSize.kt:70-72`) and skips animated, stateful, and mixed-type layers. After that cap, `CENTER_INSIDE` still fits the result into the 22 px slot. The cap is not the 15 dp scale.
- `status_bar_icon_scale_factor` is 1.0, so `ScalingDrawableWrapper` is not applied (`StatusBarIconView.java:496-498` on 16).
- `decodeStream(InputStream)` (`BitmapFactory.java:822-824` on 16) passes null options, so it does not set density. `Bitmap.mDensity` stays `getDefaultDensity()` (`Bitmap.java:102`, `:124-129`), which is `DENSITY_DEVICE`, not a hardcoded 160. `BitmapDrawable` intrinsic size is `scaleFromDensity` (`BitmapDrawable.java:239-243`, `Bitmap.java:1831-1837`).
- System icons are a different path: `IconManager` in `statusbar/phone/ui/IconManager.java` does not set `CENTER_INSIDE`. `updateIconScaleForSystemIcons` is `status_bar_system_icon_size / intrinsicHeight` (15 dp / height) times the sp ratio on android16 only (`StatusBarIconView.java:273-286` on 16, `:218-227` on 11).

## Shade small icon

This view is not `StatusBarIconView` and has no `mIconScale`.

- android16 `notification_template_header.xml:44-55`: `NotificationRowIconView`, 24 dp (`notification_icon_circle_size`, framework dimens `:382`), padding 4 dp (`:389`), oval background `notification_icon_circle.xml`. **No `android:scaleType`.** Default is `FIT_CENTER` (`ImageView` field initializer; `configureBounds` `:1356-1361`). Content box is 16×16 dp. `FIT_CENTER` **can upscale** a bitmap smaller than that box. A 2:1 whose long side is at least 16 dp draws at **16 × 8 dp**. `CENTER_INSIDE` is what forbids upscale; this layout does not set it. `clipToOutline` is on `left_icon` (`:30-42`, `centerCrop`), not on this small-icon view. `LocalImageResolver` (`:213-237`) downscales only, to `maxDrawableWidth/Height` = 24 dp.
- android11 `notification_template_header.xml:33-39`: `CachingIconView`, no padding, no scale type, no circle drawable. Size is `?attr/notificationHeaderIconSize` = `notification_header_icon_size` = **18 dp** (`themes_material.xml:1345`, framework dimens `:265`). Default `FIT_CENTER` into 18×18 dp, and it **can upscale**. A 2:1 whose long side is at least 18 dp draws at **18 × 9 dp**. `reduceImageSizes` does not cap this small icon.

## Observed emulator pixels

[Run43989808585](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/43989808585) passed API30/API36 at720×1560/280dpi. Codex42 visible-alpha bounds (threshold30 on black) are API36 B23×26px, Gsquare24×19, Gwide24×10, GthreeTwo24×14, Gcondensed23×21; API30 matches within1px. This is painted coverage, not nominal envelope: transparent source padding reduces the26px≈15dp box. Wide masks shrink with the entire layout visible; no wider slot or cropping is observed. The API36 circle shade preserves the wide glyph+digits inside its background.

Original inline measurements used an incorrect clock/bar ROI and are diagnostic; corrected `wide-icon-measured-visible-bounds.json` plus `*-measured-icon-x4.png` are derived from the retained actual probe screenshots by `tools/prototype_statusbar_wide_measure.py`. Pixel extraction uses delivered bar coordinates, not assumed15dp bounds. Real combined comparison: `docs/images/statusbar-prototype/api36-G-versus-B-measured-x4.png`.

## Contact sheet

Density 1, font scale 1, `mIconAppearAmount` = 1. Status-bar notification icon, `CENTER_INSIDE` into the 22 px slot, then × `15/22` (× `18/22` on AOD / increased):

| Intrinsic | Drawn, not AOD | AOD / increased |
|---|---|---|
| 2:1 with a side ≥ 22 px (22×11, 44×22, 30×15, 200×100) | 15 × 7.5 dp | 18 × 9 dp |
| 2:1 with both sides < 22 px (16×8) | 10.91 × 5.45 dp | 13.09 × 6.55 dp |
| square ≥ 22 px, including 192×192 and a 48 px bitmap after the android16 48 dp cap | 15 × 15 dp | 18 × 18 dp |

Shade, default `FIT_CENTER` (upscale allowed), no canvas `mIconScale`:

| Intrinsic | android16 (16 dp content box) | android11 (18 dp view) |
|---|---|---|
| 2:1 long side ≥ box | 16 × 8 dp | 18 × 9 dp |
| 2:1 smaller than the box | upscaled so the long side equals the box | same, long side 18 dp |
