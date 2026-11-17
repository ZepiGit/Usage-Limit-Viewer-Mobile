# Status bar and shade scaling of a non-square bitmap icon

Branches read: `android16-release` and `android11-release` (API 30) of `platform/frameworks/base`. Line numbers are the file as served by `?format=TEXT` on those branch heads. Density math below is what `Resources.getDimension` / `getDimensionPixelSize` do to the declared `dp`/`dip`/`sp` values; the sources do not hard-code a density bucket.

A 2:1 bitmap does **not** have one answer. On a notification icon the layout width is a positive square (`status_bar_icon_size` on API 30, `status_bar_icon_size_sp` on android16), so the aspect branch runs. It does not crop to 15×15. `ScaleType.CENTER` never scales; `configureBounds` does not fit an oversized intrinsic into the view. The 15 dp factor is only the canvas scale in `onDraw`, and only after that branch has already fit the intrinsic into the slot. A bitmap whose intrinsic width and height are both already smaller than the 22 dp slot is not enlarged (b/296026932). A 192×192 decoded stream is not a 15 dp icon: nothing in this path caps it to 15 dp before `onDraw`.

## Constants

| Name | android16-release | android11-release |
|---|---|---|
| `status_bar_icon_size` | `22dip` — `core/res/res/values/dimens.xml:102` | `22dip` — `core/res/res/values/dimens.xml:77` |
| `status_bar_icon_size_sp` | `22sp` — same file `:104`. Absent on android11. | — |
| `status_bar_system_icon_size` | `15dp` — `:106` | `15dp` — `:79` |
| `status_bar_system_icon_intrinsic_size` | `17dp` — `:109` | `17dp` — `:82` |
| `status_bar_icon_drawing_size` | `15dp` — `packages/SystemUI/res/values/dimens.xml:424` | `15dp` — `packages/SystemUI/res/values/dimens.xml:272` |
| `status_bar_icon_drawing_size_dark` | alias of `notification_header_icon_size_ambient` — SystemUI dimens `:427-428` | same alias — SystemUI dimens `:275-276` |
| `notification_header_icon_size_ambient` | `18dp` — framework dimens `:399` | `18dp` — framework dimens `:268` |
| `status_bar_icon_scale_factor` | `1.0` — SystemUI dimens `:243` | `1.0` — SystemUI dimens `:106` |
| `notification_small_icon_size` | `48dp` — framework dimens `:930`; low-ram alias `:988` | **not declared** |
| shade small-icon view size | `notification_icon_circle_size` `24dp`, padding `4dp` — framework dimens `:382`, `:389` | `notification_header_icon_size` `18dp` — framework dimens `:265` |

SystemUI re-exports the framework slot sizes: android16 `status_bar_icon_size_sp` and `status_bar_icon_size` at `packages/SystemUI/res/values/dimens.xml:151` and `:153`.

## Slot, and the width the formula actually sees

Both branches subclass `AnimatedImageView` and call `setScaleType(ScaleType.CENTER)` in the `(Context, String, StatusBarNotification, boolean)` constructor.

- android16: `packages/SystemUI/src/com/android/systemui/statusbar/StatusBarIconView.java:196`
- android11: same path `:179`

`AnimatedImageView.java` is not in `core/java/android/widget/` on either branch (fetch 404). `ImageView` default before the constructor call is `FIT_CENTER`. The status-bar view overrides it. `ImageView.configureBounds` (`core/java/android/widget/ImageView.java:1281-1362` on android16) does **not** fit an intrinsic that exceeds the view when scale type is `CENTER`:

```
mDrawable.setBounds(0, 0, intrinsicWidth, intrinsicHeight)   # native size, even if larger than the view
# CENTER: translate only
mDrawMatrix.setTranslate(round((vwidth - dwidth) * 0.5),
                         round((vheight - dheight) * 0.5))
```

`vwidth/vheight` are the view minus padding (`:1289-1290`). There is no `min(view/intrinsic, 1)` in `configureBounds`. The only scale types that scale down an oversized drawable are `CENTER_INSIDE` (`:1338-1354`), `CENTER_CROP` (`:1322-1336`), and the `setRectToRect` fit (`:1356-1361`). `CENTER` leaves pixels outside the view unscaled; the status-bar `onDraw` canvas scale is applied around that, not instead of a fit.

`ImageView.onMeasure` (`:1131-1244`) uses `mDrawableWidth/Height`, which `updateDrawable` sets from `getIntrinsicWidth/Height` (`:1077-1078`). `adjustViewBounds` is false by default, so the `CENTER` branch is the normal `resolveSizeAndState` path (`:1232-1242`): desired size is the intrinsic size plus padding. An `EXACTLY` spec from a positive `layout_width` overrides that desired size. An `AT_MOST`/`WRAP_CONTENT` spec (`layout_width = -2`) lets the view become the intrinsic width.

Who sets `LayoutParams`, and whether `lp.width > 0` in `updateIconScaleForNonSystemIcons` (`StatusBarIconView.java:231`):

- **android16 notification icons.** `NotificationIconContainerViewBinder.bindIcons` (`packages/SystemUI/src/com/android/systemui/statusbar/notification/icon/ui/viewbinder/NotificationIconContainerViewBinder.kt:188-197`):

  ```
  width  = px(status_bar_icon_size_sp) + 2 * px(status_bar_icon_horizontal_margin)
  height = statusBarHeight
  ```

  `status_bar_icon_horizontal_margin` is `0sp` (`packages/SystemUI/res/values/dimens.xml:443`). So `lp.width` is px(`22sp`), which is `> 0`. The aspect branch runs. This is not the `IconManager` `-2` width.

- **android16 system icons.** `IconManager.onCreateLayoutParams` (`packages/SystemUI/src/com/android/systemui/statusbar/phone/ui/IconManager.java:282-287`): width is `mIconSize` only when `ModesUiIcons` is on **and** `shape == FIXED_SPACE`; otherwise `WRAP_CONTENT` (`-2`). Height is always `mIconSize`. `reloadDimens` (`:294-296`) sets `mIconSize` from `status_bar_icon_size_sp`. System icons do not use `updateIconScaleForNonSystemIcons` unless the shape is `FIXED_SPACE`, so the `-2` width does not enter that branch. `FIXED_SPACE` sets width to px(`22sp`), so the branch condition passes.

- **android11 notification icons.** `NotificationIconAreaController.generateIconLayoutParams` (`packages/SystemUI/src/com/android/systemui/statusbar/phone/NotificationIconAreaController.java:190-192`):

  ```
  width  = mIconSize + 2 * mIconHPadding
  height = getHeight()
  ```

  `reloadDimens` (`:195-198`): `mIconSize = px(status_bar_icon_size)` = px(`22dip`), `mIconHPadding = px(status_bar_icon_padding)`. That padding dimen is `0dp` (`packages/SystemUI/res/values/dimens.xml:286`). `lp.width` is 22 dp in px, `> 0`. **But android11 notification scaling does not read `lp` or the intrinsic size at all** (`updateIconScaleForNotifications`, `:210-215`). The positive width matters only as the `CENTER` view box, not as an input to `mIconScale`.

- **android11 system icons.** `StatusBarIconController.IconManager` (`StatusBarIconController.java:220`, `:315-316`): width `WRAP_CONTENT` (`-2`), height px(`22dip`). `DarkIconManager` (`:124-128`) adds `status_bar_icon_padding` (`0dp`) as margins, not as width. System scaling uses intrinsic height only.

`WRAP_CONTENT` (`-2`) fails `lp.width > 0` (`StatusBarIconView.java:231`). That failure is the **system-icon** `IconManager` path on android16, which does not call this method, and any notification icon that has not yet been given the `FrameLayout.LayoutParams` above. On the notification path both branches assign a positive width before the icon is shown. Do not treat `-2` as the realized notification width.

android16 `onMeasure` (`:542-551`), **non-notification only**, multiplies the measured width by `mScaleToFitNewIconSize` and leaves height alone. Notification icons keep the `FrameLayout` width.

## android16 notification / non-system branch

`maybeUpdateIconScaleDimens` (`:211-221`) uses `updateIconScaleForNonSystemIcons` when `mNotification != null`, or when `ModesUiIcons` is enabled and `mIcon.shape == Shape.FIXED_SPACE`. Otherwise it uses the system branch.

`updateIconScaleForNonSystemIcons` (`:224-271`), only when `getDrawable() != null` and `lp.width > 0 && lp.height > 0`:

```
scaleToFitIconView = min(lp.width / intrinsicWidth, lp.height / intrinsicHeight)
if scaleToFitIconView > 1: scaleToFitIconView = 1          # CENTER never upscales

scaledImageWidth  = intrinsicWidth  * scaleToFitIconView
scaledImageHeight = intrinsicHeight * scaleToFitIconView

scaleToOriginal = min(mOriginalStatusBarIconSize / scaledImageWidth,
                      mOriginalStatusBarIconSize / scaledImageHeight)
if scaleToOriginal > 1:
    scaleToOriginal = min(scaleToOriginal, 1 / scaleToFitIconView)   # b/296026932

iconScale   = scaleToOriginal * (imageBounds / mOriginalStatusBarIconSize)
mIconScale  = iconScale * mScaleToFitNewIconSize
```

If the drawable or the positive layout size is missing, `scaleToOriginal` stays `1`, and the last two lines still run.

`reloadDimens` (`:335-354`):

- `mOriginalStatusBarIconSize` = px(`status_bar_icon_size`) = px(`22dip`)
- `mNewStatusBarIconSize` = px(`status_bar_icon_size_sp`) = px(`22sp`)
- `mScaleToFitNewIconSize` = `mNew / mOriginal`
- `imageBounds` = px(`status_bar_icon_drawing_size`) = px(`15dp`), or px(`status_bar_icon_drawing_size_dark`) = px(`18dp`) when `mIncreasedSize` (`:262-263`)

At density 1 and `fontScale` 1, `mOriginal == mNew == 22` px and `imageBounds == 15`, so the tail factor is `15/22 * 1`.

### What a 2:1 bitmap does

`onDraw` (`:556-567`) applies a second, uniform canvas scale about integer view center `(getWidth()/2, getHeight()/2)`, by `mIconScale * mIconAppearAmount` on both axes. `mIconAppearAmount` defaults to `1` (`:159`). That scale does not change aspect. `configureBounds` has already set the drawable bounds to the intrinsic size and a center translation; it has not fitted the intrinsic into the view.

`updatePivot` (`:944-950`) sets a **different** pivot, `(1 - mIconScale) / 2 * width` (or the RTL mirror) and `(height - mIconScale * width) / 2`. `onDraw` does not use it.

Notification `lp` at density 1, `fontScale` 1: width = px(`22sp`) = 22, height = status-bar height (larger than 22). `iconViewWidth/Height` in the formula are `lp.width/lp.height`, not the measured view (`:232-233`). `intrinsic*` is `Drawable.getIntrinsicWidth/Height`.

| Intrinsic (px) | `scaleToFit` = min(22/w, H/h), then ≤ 1 | scaled box | `scaleToOriginal` | `mIconScale` | Drawn size |
|---|---|---|---|---|---|
| 44×22 | min(22/44, H/22) = 0.5 | 22×11 | min(22/22, 22/11) = 1 | 15/22 | **15 × 7.5** |
| 22×11 | min(1, H/11) = 1 | 22×11 | 1 | 15/22 | **15 × 7.5** |
| 30×15 | min(22/30, H/15) = 22/30, not clamped (it is < 1) | 22×11 | min(22/22, 22/11) = 1 | 15/22 | **15 × 7.5** |
| 16×8, both sides < 22 | 1 | 16×8 | min(22/16, 22/8, 1) = **1** (b/296026932) | 15/22 | **10.91 × 5.45**, not expanded |
| 192×192 (see density section) | min(22/192, H/192) < 1 | 22×22 | 1 | 15/22 | **15 × 15** if intrinsic is 192. If intrinsic was density-scaled above 22 on both axes the same clamp applies and the drawn box is 15×15, not 15×7.5 |

The long side lands on 15 dp when at least one intrinsic side is ≥ `lp` on that axis (the `scaleToOriginal > 1` clamp does not fire). Both sides under the 22 px slot stay at intrinsic × 15/22.

`FIXED_SPACE` uses this same method and also calls `setScaleType(FIT_CENTER)` (`:415-416`). `scaleToFitIconView` is still clamped to ≤ 1 (`:243-245`), so this method does not upscale. `FIT_CENTER` then scales again inside the view. Notification icons stay on `CENTER` unless that shape flag flips them.

## android16 system-icon branch

`updateIconScaleForSystemIcons` (`:273-286`):

```
iconHeight = intrinsicHeight, or mSystemIconIntrinsicHeight (17dp) if no drawable   # getIconHeight :288-296
iconScale  = mSystemIconDesiredHeight / iconHeight          # 15dp / intrinsicHeight
mIconScale = iconScale * mScaleToFitNewIconSize             # * (22sp / 22dip)
```

Comment at `:85-87` and `:273-274`: target is one height, `17dp * (15/17) = 15dp`. Width is whatever the aspect produces. A 2:1 system bitmap of intrinsic height 17 dp draws at height 15 dp and width 30 dp, then the view width is also multiplied by `mScaleToFitNewIconSize` in `onMeasure` (`:542-551`). No min() against the other axis, no 15×7.5 special case.

`mSystemIconDefaultScale` initial value is the literal `15f/17f` (`:88-90`) and is replaced in `reloadDimens` by the px ratio (`:350-354`).

## android11 notification branch

`maybeUpdateIconScaleDimens` (`:200-207`): notification path when `mNotification != null || mAlwaysScaleIcon`. The `AttributeSet` constructor sets `mAlwaysScaleIcon = true` (`:190-195`).

`updateIconScaleForNotifications` (`:210-215`) does **not** read intrinsic width or height:

```
imageBounds = mIncreasedSize ? drawing_size_dark : drawing_size    # 18dp or 15dp, in px
mIconScale  = imageBounds / mStatusBarIconSize                     # / px(22dip)
```

No `mScaleToFitNewIconSize` on this branch (the field does not exist). `onDraw` (`:450-455`) is the same uniform canvas scale about `(width/2, height/2)`.

The view width is still the positive 22 dp slot (`generateIconLayoutParams`, above). `CENTER` does not fit the bitmap into it. `mIconScale` does not look at it either.

| Intrinsic at density 1 | `mIconScale` | Drawn size | What the 22 dp slot does |
|---|---|---|---|
| 22×11 | `15/22` | **15 × 7.5** | fits inside the slot |
| 44×22 | `15/22` | **30 × 15** | wider than the 22 dp view; `CENTER` does not shrink it first, so the canvas scale leaves it 30 dp wide and the view clips |
| 16×8 | `15/22` | **10.91 × 5.45** | not expanded |
| 15×7.5 | `15/22` | **10.23 × 5.11** | not expanded |
| 192×192 | `15/22` | **130.91 × 130.91** | not fitted to 22 dp and not capped to 15 dp. There is no `DrawableSize` and `reduceImageSizes` does not touch the small icon |

android11 does not expand a small 2:1 bitmap up to 15 dp. It also does not shrink a large one down to 15 dp. The only scale is `drawing_size / icon_size`. A 192 px bitmap that appears near 26 px was density-scaled before this factor, or it was not this view; this factor alone cannot turn 192 into ~26. `192 * (15/22) ≈ 131`, and `192 * (15/17) ≈ 169` on the system-icon height rule. Neither is ~26.

System branch (`:218-227`) matches android16's height rule without the sp factor:

```
mIconScale = status_bar_system_icon_size / intrinsicHeight    # 15dp / h
# else 15/17 when intrinsic height is 0
```

## Downscale cap (48 dp) and density

This cap changes the **bitmap**, before either matrix.

android16 `StatusBarIconView.getIcon` (`:475-499`):

1. `scaleFactor` from `R.dimen.status_bar_icon_scale_factor`. Declared `1.0`, so the `== 1.f` branch returns the drawable and does **not** wrap `ScalingDrawableWrapper` (`:496-501`). The wrapper, when used, only multiplies `getIntrinsicWidth/Height` by the factor (`packages/SystemUI/src/com/android/systemui/statusbar/ScalingDrawableWrapper.java:36-43`, identical on android11).
2. Before that, `DrawableSize.downscaleToSize(res, icon, max, max)` with `max = px(notification_small_icon_size)` = px(`48dp`), or the low-ram dimen which is an alias of the same 48 dp (`DrawableSize.kt:48-121`).

`DrawableSize` (android16 `packages/SystemUI/src/com/android/systemui/util/drawable/DrawableSize.kt`):

- Size basis is `bitmap.width/height` for a `BitmapDrawable`, else `intrinsicWidth/Height` (`:70-72`). A bitmap's **pixel** size, not its density-scaled intrinsic size.
- Returns unchanged if either side is ≤ 0, or if `width < max && height < max` (`:75-90`). Strict `<`, so a 48 px side is not resized when max is 48.
- Skips stateful, animated, and mixed-type layer drawables (`isComplicatedBitmap`, `:128-158`).
- Otherwise `scale = min(maxWidth/w, maxHeight/h)`, new size `(w*scale).toInt()` by `(h*scale).toInt()`, drawn into a new `BitmapDrawable` (`:100-124`). Aspect kept. No upscale.

A 200×100 px bitmap becomes 48×24 px. A 40×20 px bitmap is untouched. Those pixels then go through the notification formula above; intrinsic size of the new `BitmapDrawable` depends on the resources' density (the source passes `res` into the `BitmapDrawable` constructor and does not call `setTargetDensity` itself).

android11 `getIcon` (`StatusBarIconView.java:403-420`) has **no** `DrawableSize` call. `DrawableSize.kt` is not in the android11 tree (fetch 404). Only the `1.0` scale factor, so the loaded drawable is returned as-is.

`Notification.reduceImageSizes` is a second cap, on the `Icon` before shade or status bar:

- android16 (`core/java/android/app/Notification.java:7920-7934`): if `mSmallIcon` is `TYPE_BITMAP` or `TYPE_ADAPTIVE_BITMAP`, `mSmallIcon.scaleDownIfNecessary(max, max)` with the same 48 dp dimen. Other icon types are not scaled. Large icon uses `notification_right_icon_size` (also `48dp`, framework dimens `:938`).
- android11 (`Notification.java:6276-6307`): small icon is **not** in this method. Only `mLargeIcon` / `largeIcon`, at `notification_right_icon_size`.

`Icon.scaleDownIfNecessary` (android16 `graphics/java/android/graphics/drawable/Icon.java:1147-1160`, android11 `:933-946`):

```
if bitmapWidth > maxWidth || bitmapHeight > maxHeight:
    scale = min(maxWidth / bitmapWidth, maxHeight / bitmapHeight)
    createScaledBitmap(max(1, (int)(scale * bitmapWidth)), max(1, (int)(scale * bitmapHeight)), filter=true)
```

Uniform, aspect kept, no upscale. Instance method (`:1169-1174` on 16) returns immediately unless type is `TYPE_BITMAP` or `TYPE_ADAPTIVE_BITMAP`.

### `decodeStream` of a 192 px asset, and what is not a pre-draw fit

`BitmapFactory.decodeStream(InputStream)` (`graphics/java/android/graphics/BitmapFactory.java:822-824` on android16, `:805` on android11) calls `decodeStream(is, null, null)`. `setDensityFromOptions` (`:716-735` on android16) returns immediately when `opts == null`, so it does **not** set density. `Bitmap.mDensity` is initialized to `getDefaultDensity()` (`graphics/java/android/graphics/Bitmap.java:102`, `:124-129`), which is `sDefaultDensity` if already set, else `DisplayMetrics.DENSITY_DEVICE`. It is not hardcoded to 160 unless `DENSITY_DEVICE` is 160. `decodeResourceStream` (`:579-586`) is the path that sets `inDensity` from the resource's `TypedValue.density` (0 meaning `DENSITY_DEFAULT` = 160) and `inTargetDensity` from `res.getDisplayMetrics().densityDpi`. `Icon.loadDrawableInner` for `TYPE_URI` / `TYPE_URI_ADAPTIVE_BITMAP` uses the no-options `decodeStream` (`Icon.java:499` and `:515` on android16). android11 `Icon.java` has no `fixMaxBitmapSize`.

`BitmapDrawable.computeBitmapSize` (`BitmapDrawable.java:239-243` on android16, `:232-236` on android11):

```
mBitmapWidth  = bitmap.getScaledWidth(mTargetDensity)
mBitmapHeight = bitmap.getScaledHeight(mTargetDensity)
```

`getScaledWidth` (`Bitmap.java:1805-1806`) is `scaleFromDensity(width, bitmapDensity, targetDensity)` (`:1831-1837`):

```
if sourceDensity == DENSITY_NONE or target == DENSITY_NONE or source == target: return size
else return (size * targetDensity + (sourceDensity >> 1)) / sourceDensity
```

`mTargetDensity` comes from `Drawable.resolveDensity(res, …)` in `updateLocalState` (`BitmapDrawable.java:1078-1081`). A `BitmapDrawable(Resources, Bitmap)` therefore reports intrinsic size `192 * displayDensityDpi / bitmapDensity`, rounded. At density 160 with `DENSITY_DEVICE` 160 the ratio is 1, so intrinsic stays 192. At 480 dpi / bitmap density 160, intrinsic is `192 * 480 / 160 = 576`. The 15 dp canvas factor then runs on that intrinsic size. It does not replace it.

`Icon.fixMaxBitmapSize` (android16 only, `Icon.java:411-428`) resizes only when `bitmap.getByteCount() > RecordingCanvas.MAX_BITMAP_SIZE`, by pixel-aspect, not to 48 dp or 15 dp. A 192×192 ARGB bitmap is 147456 bytes, under any canvas limit that accepts a normal icon. android11 does not have this method.

There is no `maybeProcessSmallIconAsLarge` and no `fixSmallIcon` on either `Notification.java`. `getProfileBadge` (android16 `:5897-5917`, android11 `:4720-4738`) decodes the profile badge into `R.id.profile_badge`. It is not the status-bar small icon and it does not scale `R.id.icon`. `processSmallIconColor` only sets tint / background color (android16 `:7418-7427`, android11 `:5833-5848`).

The pre-draw caps that do exist, restated against a 192 px bitmap:

| Step | android16 | android11 |
|---|---|---|
| `Notification.reduceImageSizes` on the small icon | `TYPE_BITMAP` and `TYPE_ADAPTIVE_BITMAP` only, `scaleDownIfNecessary(px(48dp), px(48dp))` (`Notification.java:7926-7934`). A 192×192 bitmap becomes 48×48 **bitmap pixels**, aspect kept. Resource and URI icons are not scaled here. | small icon is not touched (`:6276-6307`) |
| `DrawableSize.downscaleToSize` in `StatusBarIconView.getIcon` | `BitmapDrawable` uses `bitmap.width/height`, not intrinsic (`DrawableSize.kt:70-72`). 192 ≥ px(48dp) at mdpi, so scale to 48×48. At xxhdpi, px(48dp) = 144, so 192 becomes 144 bitmap px. Skipped for animated/stateful/mixed layers. | method does not exist |
| `configureBounds` `CENTER` | no fit | no fit |
| `lp.width > 0` aspect branch | runs for notification icons because the binder sets width to px(22sp) + 2×0sp. 48×48 intrinsic then draws at 15×15 dp, not 15×7.5 | branch does not exist. 192 intrinsic draws at 192×15/22 |

A prior 192×192 asset that measured about 42 px and then about 26 px on an API 30 device is **not** produced by `updateIconScaleForNotifications` alone (`192 * 15/22 ≈ 131`). The source does not contain a second fit that would turn 192 into 42. 42 px is px(15 dp) at density ~2.8, or px(22 dp) at ~1.9; those are the dimen values, not a 192 px bitmap after this scale. [INFERENCE] the captured 42 px icon was already the framework 15 dp (or 22 dp) slot at that density, and the ~26 px result is that slot times `15/22` (22 × 15/22 = 15, and 15 dp at the same density is ~26 px if 22 dp was ~39–42 px). That matches the **system-icon** rule only when intrinsic height is the 17 dp asset (`15/17 * 17 = 15` dp, ~26 px), or the notification rule when intrinsic height is already the 22 dp slot. It does not match a 192 px intrinsic.

Density / font:

- Every `dp`/`dip`/`sp` dimen above is converted with `getDimensionPixelSize` or `getDimension` (`reloadDimens`). `dp` scales with density. On android16 the slot **height** and `mScaleToFitNewIconSize` use `22sp`, so they also scale with `fontScale`; `mOriginal` stays `22dip`. `onConfigurationChanged` treats `CONFIG_DENSITY | CONFIG_FONT_SCALE` as a full `updateIconDimens` (`StatusBarIconView.java:313-329`).
- android11 `onConfigurationChanged` reloads when `densityDpi` changes (`:249-258`). There is no sp slot size.
- `onDraw` uses integer division for the pivot (`getWidth()/2`), discarding the fraction (`:558-560` on 16; the comment states that).

## Shade small-icon circle

### android16

Header layout `core/res/res/layout/notification_template_header.xml:44-55`:

```xml
<com.android.internal.widget.NotificationRowIconView
    android:id="@+id/icon"
    android:layout_width="@dimen/notification_icon_circle_size"      <!-- 24dp -->
    android:layout_height="@dimen/notification_icon_circle_size"
    android:background="@drawable/notification_icon_circle"
    android:padding="@dimen/notification_icon_circle_padding"        <!-- 4dp -->
    android:maxDrawableWidth="@dimen/notification_icon_circle_size"
    android:maxDrawableHeight="@dimen/notification_icon_circle_size"
    />
```

No `android:scaleType`. ImageView default `FIT_CENTER`. `notification_icon_circle.xml` is an oval shape (`core/res/res/drawable/notification_icon_circle.xml:18-20`). The small-icon view does **not** set `clipToOutline`. The sibling `left_icon` (`:30-42`) does: `ImageView`, 24 dp, `scaleType="centerCrop"`, `clipToOutline="true"`, `visibility="gone"`. That is the large/left icon, not the small icon.

`NotificationRowIconView` extends `CachingIconView` (`core/java/com/android/internal/widget/NotificationRowIconView.java:33`). `setImageIcon` goes to `CachingIconView.setImageIcon` (`CachingIconView.java:101-118`), which loads through `LocalImageResolver.resolveImage(icon, context, maxDrawableWidth, maxDrawableHeight)`.

`LocalImageResolver` (`core/java/com/android/internal/widget/LocalImageResolver.java`):

- Bitmap (`:175-193`): if either pixel side exceeds max (here px of 24 dp), `Icon.scaleDownIfNecessary(max, max)` — same uniform min-ratio, no upscale — then `loadDrawable`.
- Decoded resources (`:213-237`): no upscale. If width ≥ height and width > maxWidth, `targetHeight = height * maxWidth / width` (integer). Else if height > maxHeight, `targetWidth = width * maxHeight / height`. A 2:1 image larger than 24 dp on the long side becomes long-side = 24 dp, short side = half. A 2:1 image already under 24 dp is unchanged.

Content box after 4 dp padding on each side: **16 dp × 16 dp**. `FIT_CENTER` (`ImageView.configureBounds`, the `setRectToRect` branch at `:1356-1361`, `ScaleToFit.CENTER`) fits the intrinsic rectangle inside that box, uniform, centered. A 2:1 drawable draws at **16 × 8 dp** inside the 24 dp view. It is not cropped to a square and not cropped to the oval, because this view never sets `clipToOutline`. The oval is only the background behind the padded content.

`Notification.Builder.bindSmallIcon` (`Notification.java:6481-6486`) does `setImageViewIcon(R.id.icon, mN.mSmallIcon)` plus `processSmallIconColor` (`:7418-7427`): `setBackgroundColor` and `setOriginalIconColor`. No scale, matrix, or clip there.

App-icon override (`NotificationRowIconView.setImageIcon`, `:86-104`, flag `notificationsRedesignAppIcons`): replaces the drawable with the app icon and `adjustViewForAppIcon` sets padding to 0 and background to null (`:134-163`). The 24 dp view then FIT_CENTERs the app icon into the full 24 dp with no circle. The class does not apply `StatusBarIconView.APP_ICON_SCALE` (0.75f, `StatusBarIconView.java:97`); that constant is not referenced in `NotificationRowIconView`.

### android11

Header layout `core/res/res/layout/notification_template_header.xml:33-39`:

```xml
<com.android.internal.widget.CachingIconView
    android:id="@+id/icon"
    android:layout_width="?attr/notificationHeaderIconSize"
    android:layout_height="?attr/notificationHeaderIconSize"
    android:layout_gravity="center" />
```

No padding, no background, no `scaleType`, no `maxDrawableWidth`, no `clipToOutline`. `notification_icon_circle.xml` is not in this tree (fetch 404).

`?attr/notificationHeaderIconSize` is `@dimen/notification_header_icon_size` (`core/res/res/values/themes_material.xml:1345`), which is `18dp` (framework dimens `:265`). `Notification.Header` style (`styles_material.xml:1302-1309`) sets header padding only, not the icon.

android11 `CachingIconView` (`core/java/com/android/internal/widget/CachingIconView.java`) has no `mMaxDrawableWidth` and `setImageIcon` (`:60`) does not call `LocalImageResolver`. `LocalImageResolver.java` is not how this view loads icons on this branch.

`bindSmallIcon` (`Notification.java:5280-5286`) is `setImageViewIcon` + `processSmallIconColor` (`:5833-5848`): tint `SRC_ATOP` when the icon is colorable, and `setOriginalIconColor`. No matrix or clip.

Default `FIT_CENTER` in an 18×18 dp view: a 2:1 bitmap keeps its aspect, long side = 18 dp, short side = 9 dp, centered. Nothing in this header turns that into a circle. `NotificationHeaderView` itself (`core/java/android/view/NotificationHeaderView.java`) measures and shrinks text; the only `48 * density` value is a touch-slop rectangle (`:418` on android11, `:326` on android16), not an icon size.

## Contact-sheet sizes to simulate

Render at mdpi (density 1, `fontScale` 1) so 1 px = 1 dp, and label the bitmap's **intrinsic** size after the 48 dp cap. Status-bar canvas pivot is the view center, not `updatePivot`.

Status bar, notification icon, `mIconAppearAmount = 1`, `mIncreasedSize = false`:

Slot width is px(22sp) on android16 notifications and px(22dip) on android11 notifications. It is not `-2`. `CENTER` does not pre-fit.

| Sheet cell | android16 notification drawn (dp) | android11 notification drawn (dp) |
|---|---|---|
| 2:1 intrinsic 22×11 | 15 × 7.5 inside the 22-wide slot | 15 × 7.5 inside the 22-wide slot |
| 2:1 intrinsic 44×22 | 15 × 7.5 (aspect branch fits to the slot, then ×15/22) | 30 × 15, clipped by the 22-wide view (`CENTER` does not fit first) |
| 2:1 intrinsic 16×8 (both sides < 22) | 10.91 × 5.45 (b/296026932, not expanded) | 10.91 × 5.45 (no expand) |
| 2:1 intrinsic 200×100, `TYPE_BITMAP` | `reduceImageSizes` / `DrawableSize` to long side px(48dp), then aspect branch: **15 × 7.5** | no cap; `(200×15/22) × (100×15/22)` = 136.36 × 68.18, clipped to the 22-wide view |
| square intrinsic 192×192, `TYPE_BITMAP`, mdpi | capped to 48×48, then **15 × 15** | **130.91 × 130.91**, clipped to the 22-wide view. Not ~26 px |
| square 22×22 (control) | 15 × 15 | 15 × 15 |
| system icon, intrinsic height 17, 2:1 | height 15, width 30, then × `(22sp/22dip)` | height 15, width 30. This is the path whose 17 dp asset becomes 15 dp (~26 px when 22 dp is ~39 px) |

Shade small icon, same bitmaps, `FIT_CENTER`, no extra canvas scale:

| Sheet cell | android16 | android11 |
|---|---|---|
| view | 24×24 dp, 4 dp padding, oval background, **no** clip | 18×18 dp, no padding, no circle |
| content box | 16×16 dp | 18×18 dp |
| 2:1 of any size whose long side ≥ content box | **16 × 8** centered in the 24 dp view | **18 × 9** |
| 2:1 already smaller than the content box | drawn at intrinsic size, centered (FIT_CENTER and LocalImageResolver do not upscale) | same, centered in 18 dp |
| bitmap pixels > 48×48, `TYPE_BITMAP` | `Icon.scaleDownIfNecessary` to long side 48 px before the view; view still fits into 16 dp | small icon is not passed through `reduceImageSizes` |

Ambient / increased status-bar drawing size, if a sheet includes it: swap 15 dp for 18 dp in the `imageBounds / 22dip` factor only. android16 tail becomes `18/22` times the same `scaleToOriginal`. android11 `mIconScale` becomes `18/22`.
