package id.harness.pipocket.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import id.harness.pipocket.AppState
import id.harness.pipocket.ImageRef
import id.harness.pipocket.ImageViewerRequest
import id.harness.pipocket.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 看图器（全屏）。用户 2026-10-24：「太简陋，好好设计一下，看看网上的项目」。
 *
 * 参照：
 * - **Material 3 · Full-screen dialog**（m3.material.io/components/dialogs/guidelines）：
 *   全屏对话框里**左上角 ✕ 是唯一导航方式**，其余都是动作 —— 所以顶部只有关闭 + 信息 + 动作，
 *   没有返回箭头、没有抽屉。
 * - **PhotoView**（github.com/Baseflow/PhotoView）的惯用手势集：多指缩放、双击缩放、
 *   放大后平滑拖动；**点图片外关闭**（`OnOutsidePhotoTapListener`）而不是点图片就关。
 * - 常见相册的「点一下隐藏 chrome、再点回来」专注看图。
 *
 * 本文件只做只读浏览（不编辑/不保存）：捏合 1×–4×、拖动带边界钳制、双击 1×⇄2.5×（缓动）、
 * 单击在放大时复位、点黑底关闭；多张图有页码、底部胶片条、体积/尺寸信息。
 *
 * 纯数学（钳制/锚点缩放/命中测试/体积估算）在 [ImageViewerMath]，JVM 单测覆盖。
 */
internal object ImageViewerMath {
    /** 缩小的下限：fit 的 1/4 —— 用户 2026-10-24：「缩小也要支持」（不只放大）。 */
    const val MIN_SCALE = 0.25f
    /** 1×＝刚好铺满视口（fit），也是「复位」的目标。 */
    const val FIT_SCALE = 1f
    const val MAX_SCALE = 4f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** 双击目标倍率：不是 1× 就回 1×（放大或缩小都回来），否则放到 [DOUBLE_TAP_SCALE]。 */
    fun doubleTapTarget(current: Float): Float =
        if (current > FIT_SCALE * 1.01f || current < FIT_SCALE * 0.99f) FIT_SCALE else DOUBLE_TAP_SCALE

    /** 当前是不是 1×（用来决定要不要显示「复位」/点图复位）。 */
    fun isFit(scale: Float): Boolean = kotlin.math.abs(scale - FIT_SCALE) <= 0.01f

    /** 图片在视口内“fit”后的显示尺寸（保持宽高比）。 */
    fun fitSize(intrinsic: Size, viewport: Size): Size {
        if (intrinsic.width <= 0f || intrinsic.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) {
            return Size(0f, 0f)
        }
        val scale = min(viewport.width / intrinsic.width, viewport.height / intrinsic.height)
        return Size(intrinsic.width * scale, intrinsic.height * scale)
    }

    /** 当前倍率下允许的最大平移量（半宽/半高）；缩放到 1× 或图片比视口小 → 0。 */
    fun maxPan(display: Size, viewport: Size, scale: Float): Offset {
        val maxX = max(0f, (display.width * scale - viewport.width) / 2f)
        val maxY = max(0f, (display.height * scale - viewport.height) / 2f)
        return Offset(maxX, maxY)
    }

    /** 把平移量钳到边界内（放大后不允许把图拖出视口）。 */
    fun clampPan(offset: Offset, limit: Offset): Offset = Offset(
        offset.x.coerceIn(-limit.x, limit.x),
        offset.y.coerceIn(-limit.y, limit.y),
    )

    /**
     * 以 [focal]（相对视口中心的偏移）为锚把倍率从 [oldScale] 变到 [newScale]，
     * 保持锚点下的内容不动：`o' = f - (f - o) * (new / old)`。
     */
    fun zoomAround(offset: Offset, focal: Offset, oldScale: Float, newScale: Float): Offset {
        if (oldScale <= 0f) return offset
        val ratio = newScale / oldScale
        return Offset(
            focal.x - (focal.x - offset.x) * ratio,
            focal.y - (focal.y - offset.y) * ratio,
        )
    }

    /** 点击位置是否落在（缩放/平移后的）图片上：图片外算“点黑底”。 */
    fun hitTestImage(point: Offset, viewport: Size, display: Size, scale: Float, offset: Offset): Boolean {
        if (display.width <= 0f || display.height <= 0f) return false
        val center = Offset(viewport.width / 2f + offset.x, viewport.height / 2f + offset.y)
        val halfW = display.width * scale / 2f
        val halfH = display.height * scale / 2f
        return abs(point.x - center.x) <= halfW && abs(point.y - center.y) <= halfH
    }

    /** 页码归一（数据变短/初始页越界时都不崩）。 */
    fun pageOf(index: Int, count: Int): Int = if (count <= 0) 0 else index.coerceIn(0, count - 1)

    /** base64 估算原始字节数（4 字符 ≈ 3 字节）。 */
    fun approxBytes(base64Length: Int): Long = if (base64Length <= 0) 0 else base64Length.toLong() * 3 / 4

    /** 人类可读体积：`161 KB` / `1.2 MB`。 */
    fun humanBytes(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024.0).roundToInt()} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** 顶部那条信息：`720×1600 · 158 KB · png`（缺的不显示）。 */
    fun infoLine(width: Int, height: Int, base64Length: Int, mimeType: String): String {
        val dims = if (width > 0 && height > 0) "${width}×${height}" else ""
        val bytes = humanBytes(approxBytes(base64Length))
        val mime = mimeType.substringAfter('/', "")
        return listOf(dims, bytes, mime).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

/**
 * 全屏看图（覆盖在 app 根布局上层，**不用 Dialog**）。
 *
 * 为什么不用 Dialog：系统栏只能由**应用自己的窗口**真正隐藏 —— Dialog 是独立窗口，
 * `WindowInsetsControllerCompat.hide()` 与 `FLAG_FULLSCREEN` 在这台机器（ColorOS/Android 16）
 * 上都不生效（2026-10-24 实测两次，dumpsys 里 dialog 窗口毫无变化）。
 * 改成 overlay 后，隐藏系统栏就是操作 Activity 窗口，一次就生效。
 */
@Composable
fun ViewerOverlays() {
    // 文件面板在下，看图在上（从文件面板里点图进全屏）
    if (AppState.fileView != null) FileViewerOverlay()
    AppState.imageViewer?.let { req ->
        ImageViewerOverlay(req) { AppState.closeImageViewer() }
    }
}

@Composable
private fun ImageViewerOverlay(request: ImageViewerRequest, onDismiss: () -> Unit) {
    val images = request.images
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = ImageViewerMath.pageOf(request.initial, images.size),
        pageCount = { images.size },
    )
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }
    val page = pagerState.currentPage.coerceIn(0, images.lastIndex)
    val title = request.titles.getOrNull(page).orEmpty()
    val ref = images[page]

    ImmersiveSystemBars()
    BackHandler { onDismiss() }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                ZoomableImagePage(
                    ref = images[index],
                    active = pagerState.currentPage == index,
                    onTapImage = { chrome = !chrome },
                    onDismiss = onDismiss,
                )
            }

            if (chrome) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black)   // 纯黑：图片自带的状态行一点都不透，顶部才干净
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChromePill(icon = R.drawable.ic_close, label = "关闭", onClick = onDismiss)
                    InfoBlock(ref = ref, title = title, modifier = Modifier.weight(1f))
                    if (images.size > 1) {
                        Surface(
                            color = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White,
                            shape = CircleShape,
                        ) {
                            Text(
                                "${page + 1} / ${images.size}",
                                style = MaterialTheme.typography.labelMedium
                                    .copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            if (images.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    itemsIndexed(images) { index, item ->
                        val selected = page == index
                        val shape = RoundedCornerShape(12.dp)
                        Surface(
                            shape = shape,
                            color = if (selected) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.08f),
                            border = if (selected) BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)) else null,
                            modifier = Modifier
                                .size(if (selected) 58.dp else 50.dp)
                                .clip(shape),
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                val bitmap = rememberDecodedBitmap(item.base64, 160)
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "第 ${index + 1} 张",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(shape)
                                            .clickable {
                                                scope.launch { pagerState.animateScrollToPage(index) }
                                            },
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBlock(ref: ImageRef, title: String, modifier: Modifier = Modifier) {
    // 用**原图**尺寸（只读文件头，不解码像素）：以前拿解码后的 512 缩略图量 ⇒ 显示成 180×400
    val dims = rememberOriginalSize(ref.base64)
    val info = ImageViewerMath.infoLine(
        width = dims?.first ?: 0,
        height = dims?.second ?: 0,
        base64Length = ref.base64.length,
        mimeType = ref.mimeType,
    )
    Column(modifier = modifier.padding(horizontal = 10.dp)) {
        if (title.isNotBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 8f),
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (info.isNotBlank()) {
            Text(
                info,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 8f),
                ),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChromePill(icon: Int, label: String, onClick: () -> Unit) {
    Surface(color = Color.White.copy(alpha = 0.14f), contentColor = Color.White, shape = CircleShape) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ZoomableImagePage(
    ref: ImageRef,
    active: Boolean,
    onTapImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bitmap = rememberDecodedBitmap(ref.base64, 2048)
    var viewport by remember { mutableStateOf(Size.Zero) }
    var scale by remember(ref.base64) { mutableStateOf(ImageViewerMath.FIT_SCALE) }   // 1× = 铺满视口（MIN_SCALE 现在是最小缩小倍数，不能拿来当初始值）
    var offset by remember(ref.base64) { mutableStateOf(Offset.Zero) }
    val intrinsic = bitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
    val display = remember(intrinsic, viewport) { ImageViewerMath.fitSize(intrinsic, viewport) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(ref.base64, display, viewport, active) {
                if (!active) return@pointerInput
                detectTapGestures(
                    onTap = { position ->
                        // 与 PhotoView 的 outside-tap 一致：点图外关闭，点图片本身不动/复位
                        val inside = ImageViewerMath.hitTestImage(position, viewport, display, scale, offset)
                        when {
                            !inside -> onDismiss()
                            !ImageViewerMath.isFit(scale) -> {
                                offset = Offset.Zero
                                scope.launch {
                                    val anim = androidx.compose.animation.core.Animatable(scale)
                                    anim.animateTo(ImageViewerMath.FIT_SCALE, tween(180)) { scale = value }
                                }
                            }
                            else -> onTapImage()   // 1× 时点图 = 切 chrome（专注看图）
                        }
                    },
                    onDoubleTap = { position ->
                        val target = ImageViewerMath.doubleTapTarget(scale)
                        val focal = Offset(position.x - viewport.width / 2f, position.y - viewport.height / 2f)
                        // 无边界：不做 clamp，双击放大后随便拖
                        offset = ImageViewerMath.zoomAround(offset, focal, scale, target)
                        scope.launch {
                            val anim = androidx.compose.animation.core.Animatable(scale)
                            anim.animateTo(target, tween(200)) { scale = value }
                        }
                    },
                )
            }
            .pointerInput(ref.base64, display, viewport, active) {
                if (!active) return@pointerInput
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val target = (scale * gestureZoom).coerceIn(ImageViewerMath.MIN_SCALE, ImageViewerMath.MAX_SCALE)
                    val focal = Offset(centroid.x - viewport.width / 2f, centroid.y - viewport.height / 2f)
                    val zoomed = ImageViewerMath.zoomAround(offset, focal, scale, target)
                    scale = target
                    // 用户 2026-10-24：「全屏支持无边界拖拽」——不钳制，图可以拖出视口任意位置；
                    // 拖飞了用右下角「复位」一键回中。
                    offset = zoomed + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            ref.base64.isBlank() -> DecodeFailureText()
            bitmap == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .widthIn(min = 120.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
                Text(
                    "解码中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            else -> Image(
                bitmap = bitmap,
                contentDescription = "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }

        // 放大后给一个「复位」小胶囊（不然只能靠双击/再点一下猜）
        if (!ImageViewerMath.isFit(scale)) {
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) {
                Text(
                    "复位",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clickable {
                            offset = Offset.Zero
                            scope.launch {
                                val anim = androidx.compose.animation.core.Animatable(scale)
                                anim.animateTo(ImageViewerMath.FIT_SCALE, tween(180)) { scale = value }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private val sizeCache = object : LinkedHashMap<String, Pair<Int, Int>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>?): Boolean = size > 32
}

/** 原图尺寸：`inJustDecodeBounds` 只读文件头，几毫秒；缓存按 base64 前 64 字符 + 长度做 key。 */
@Composable
internal fun rememberOriginalSize(base64: String): Pair<Int, Int>? {
    val key = base64.length.toString() + ":" + base64.take(64)
    val state by produceState(initialValue = synchronized(sizeCache) { sizeCache[key] }, key1 = key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
                if (bytes == null) null else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
                }
            }
            value?.let { v -> synchronized(sizeCache) { sizeCache[key] = v } }
        }
    }
    return state
}

/**
 * **非全屏**也能缩放：文件面板里的内联预览用这个（用户 2026-10-24：「非全屏缩小放大也要支持」）。
 *
 * 与全屏看图同一套数学（[ImageViewerMath]）：捏合 0.25×–4×、无边界拖动、双击 1×⇄2.5×。
 * 单击仍然是「进全屏」，所以放大/缩小只走双指与双击，避免和点按抢事件。
 */
@Composable
internal fun InlineZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf(Size.Zero) }
    var scale by remember(bitmap) { mutableStateOf(ImageViewerMath.FIT_SCALE) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val intrinsic = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    val display = remember(intrinsic, viewport) { ImageViewerMath.fitSize(intrinsic, viewport) }

    Box(
        modifier = modifier
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap, display, viewport) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { position ->
                        val target = ImageViewerMath.doubleTapTarget(scale)
                        val focal = Offset(position.x - viewport.width / 2f, position.y - viewport.height / 2f)
                        offset = ImageViewerMath.zoomAround(offset, focal, scale, target)
                        scope.launch {
                            val anim = androidx.compose.animation.core.Animatable(scale)
                            anim.animateTo(target, tween(200)) { scale = value }
                        }
                    },
                )
            }
            .pointerInput(bitmap, display, viewport) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val target = (scale * gestureZoom).coerceIn(ImageViewerMath.MIN_SCALE, ImageViewerMath.MAX_SCALE)
                    val focal = Offset(centroid.x - viewport.width / 2f, centroid.y - viewport.height / 2f)
                    val zoomed = ImageViewerMath.zoomAround(offset, focal, scale, target)
                    scale = target
                    offset = zoomed + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        if (!ImageViewerMath.isFit(scale)) {
            Surface(
                color = Color.Black.copy(alpha = 0.35f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
            ) {
                Text(
                    "复位",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clickable {
                            offset = Offset.Zero
                            scope.launch {
                                val anim = androidx.compose.animation.core.Animatable(scale)
                                anim.animateTo(ImageViewerMath.FIT_SCALE, tween(180)) { scale = value }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** base64 → 解码后的位图（IO 线程 + 小缓存；组合期不再同步解大图）。 */
@Composable
internal fun rememberDecodedBitmap(base64: String, maxDim: Int): ImageBitmap? {
    val key = maxDim.toString() + ":" + base64.length + ":" + base64.hashCode()
    val state by produceState(initialValue = imageCache[key], key1 = key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { decodeBase64Image(base64, maxDim) }
            if (value != null) rememberInCache(key, value!!)
        }
    }
    return state
}

private const val CACHE_LIMIT = 12
private val imageCache = object : LinkedHashMap<String, ImageBitmap>(CACHE_LIMIT, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > CACHE_LIMIT
}

private fun rememberInCache(key: String, bitmap: ImageBitmap) {
    synchronized(imageCache) { imageCache[key] = bitmap }
}

/** 解码 base64 图（按 [maxDim] 做 inSampleSize 降采样，避免大图 OOM）。 */
internal fun decodeBase64Image(base64: String, maxDim: Int): ImageBitmap? {
    val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    // 兜底：极端长图（1×N）降采样后仍然很大时再收一轮
    val pixels = (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample).toLong()
    if (pixels > 24_000_000L) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}

/** 缩略图尺寸基准（供 ToolImages 复用，保持两处一致）。 */
internal fun thumbSizeDp(base64Length: Int): Int = if (base64Length > 400_000) 320 else 512

/** 便于测试/调用方把 Int 尺寸换成 Size。 */
internal fun sizeOf(width: Int, height: Int): Size = Size(width.toFloat(), height.toFloat())

/** 浮点像素取整（Compose 布局用，避免亚像素抖动）。 */
internal fun px(value: Float): Int = value.roundToInt()

@Composable
internal fun DecodeFailureText() {
    Text(
        text = "这张图解码不了（可能已损坏）",
        color = Color.White.copy(alpha = 0.8f),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}
