package app.gymquest.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's own minimalist line icons (24x24 grid, 2 px round strokes), drawn as vectors so they take the
 * colour of the text next to them and need no image files. Written as SVG path data.
 */
object Ico {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    val home: ImageVector by lazy { icon("home", "M3 11.2L12 3.5l9 7.7M5.5 9.6V20h4.5v-5.5h4V20h4.5V9.6") }
    val train: ImageVector by lazy { icon("train", "M6.5 6.5v11M17.5 6.5v11M3.5 9.5v5M20.5 9.5v5M6.5 12h11") }
    val map: ImageVector by lazy { icon("map", "M9 4L3 6.2V20l6-2.2 6 2.2 6-2.2V4l-6 2.2zM9 4v13.8M15 6.2V20") }
    val life: ImageVector by lazy { icon("life", "M20 4C11 4 5.5 8.5 5.5 15c0 1.6.4 3 1.2 4.2C7.9 19.7 9.1 20 10.5 20 17 20 20 13.5 20 4zM5 21c2-4.6 5-7.6 9.5-10") }
    val community: ImageVector by lazy { icon("community", "M16.5 20v-1.5a4 4 0 0 0-4-4h-5a4 4 0 0 0-4 4V20M10 10.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM21 20v-1.5a4 4 0 0 0-3-3.9M15.5 3.2a3.5 3.5 0 0 1 0 6.6") }
    val coin: ImageVector by lazy { icon("coin", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0zM12 7v10M14.8 9.4c-.4-.9-1.4-1.4-2.8-1.4-1.6 0-2.7.8-2.7 1.9 0 2.7 5.6 1.2 5.6 4 0 1.1-1.1 1.9-2.9 1.9-1.4 0-2.5-.5-3-1.5") }
    val flame: ImageVector by lazy { icon("flame", "M12 3c.8 3.2-2.6 4.8-2.6 8.3a2.6 2.6 0 0 0 5.2 0c0-1-.3-1.8-.9-2.7 2.8 1.2 4.6 3.6 4.6 6.4A6.3 6.3 0 0 1 12 21.5a6.3 6.3 0 0 1-6.3-6.3C5.7 10.2 9.8 8 12 3z") }
    val check: ImageVector by lazy { icon("check", "M5 12.5l4.5 4.5L19 7.5") }
    val check_circle: ImageVector by lazy { icon("check_circle", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0zM8 12.4l2.8 2.8L16 9.8") }
    val lock: ImageVector by lazy { icon("lock", "M7.5 10.5h9a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-9a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2zM8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5M12 15.5h.01") }
    val trophy: ImageVector by lazy { icon("trophy", "M8 4h8v5a4 4 0 0 1-8 0zM8 6H4.5v1.2A3 3 0 0 0 8 10.2M16 6h3.5v1.2a3 3 0 0 1-3.5 3M12 13v4M8.5 20h7M10 17h4") }
    val gift: ImageVector by lazy { icon("gift", "M5 10h14a1 1 0 0 1 1 1v8.5a1 1 0 0 1 -1 1h-14a1 1 0 0 1 -1 -1v-8.5a1 1 0 0 1 1 -1zM3 7h18v3H3zM12 7v13.5M12 7C9.5 7 7.5 6 7.5 4.7S9 3.2 10.2 4c1 .7 1.8 1.9 1.8 3zM12 7c2.5 0 4.5-1 4.5-2.3S15 3.2 13.8 4c-1 .7-1.8 1.9-1.8 3z") }
    val timer: ImageVector by lazy { icon("timer", "M4.5 13.5a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0zM12 9.5v4l2.5 1.5M9.5 2.5h5M12 2.5V6") }
    val sparkles: ImageVector by lazy { icon("sparkles", "M11 3.5l1.9 5.1 5.1 1.9-5.1 1.9L11 17.5 9.1 12.4 4 10.5l5.1-1.9zM18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z") }
    val award: ImageVector by lazy { icon("award", "M6 9a6 6 0 1 0 12 0a6 6 0 1 0 -12 0zM8.6 14L7 21l5-3 5 3-1.6-7") }
    val pulse: ImageVector by lazy { icon("pulse", "M3 12h4l2.5-7 5 14 2.5-7H21") }
    val moon: ImageVector by lazy { icon("moon", "M20 14.5A8.5 8.5 0 1 1 9.5 4a6.6 6.6 0 0 0 10.5 10.5z") }
    val phone: ImageVector by lazy { icon("phone", "M8.5 3h7a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2zM10.5 18h3") }
    val phone_off: ImageVector by lazy { icon("phone_off", "M8.5 3h7a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2zM10.5 18h3M3 3l18 18") }
    val droplet: ImageVector by lazy { icon("droplet", "M12 3c3.6 4.3 6 7.3 6 10.6a6 6 0 0 1-12 0C6 10.3 8.4 7.3 12 3z") }
    val headphones: ImageVector by lazy { icon("headphones", "M4 15v-3a8 8 0 0 1 16 0v3M4 15h3.2v5H5.5A1.5 1.5 0 0 1 4 18.5zM20 15h-3.2v5h1.7a1.5 1.5 0 0 0 1.5-1.5z") }
    val pause: ImageVector by lazy { icon("pause", "M8.5 5v14M15.5 5v14") }
    val camera: ImageVector by lazy { icon("camera", "M4 8.5h3l1.6-2.5h6.8L17 8.5h3V19H4zM12 16.8a3.4 3.4 0 1 0 0-6.8 3.4 3.4 0 0 0 0 6.8z") }
    val flip: ImageVector by lazy { icon("flip", "M4.5 10a8 8 0 0 1 14-3.2M19 3.5v3.6h-3.6M19.5 14a8 8 0 0 1-14 3.2M5 20.5v-3.6h3.6") }
    val battery: ImageVector by lazy { icon("battery", "M4.5 8h12a1.5 1.5 0 0 1 1.5 1.5v5a1.5 1.5 0 0 1 -1.5 1.5h-12a1.5 1.5 0 0 1 -1.5 -1.5v-5a1.5 1.5 0 0 1 1.5 -1.5zM20.5 11v2M6 11v2M9 11v2") }
    val square: ImageVector by lazy { icon("square", "M7.5 5h9a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5z") }
    val clipboard: ImageVector by lazy { icon("clipboard", "M7.5 5h9a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-9a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2zM9 3h6v4H9zM9 12h6M9 16h4") }
    val calendar: ImageVector by lazy { icon("calendar", "M6 5.5h12a2 2 0 0 1 2 2v11a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2zM4 10h16M8.5 3v4M15.5 3v4") }
    val swords: ImageVector by lazy { icon("swords", "M4.5 4.5l12 12M12 16l4-4M16.5 16.5l3 3M19.5 4.5l-12 12M12 16l-4-4M7.5 16.5l-3 3") }
    val shield: ImageVector by lazy { icon("shield", "M12 3l7.5 2.7v5.8c0 4.6-3.1 7.8-7.5 9.5-4.4-1.7-7.5-4.9-7.5-9.5V5.7z") }
    val bolt: ImageVector by lazy { icon("bolt", "M13 2.5L5 13.5h6.2L10.5 21.5 19 10.5h-6.2z") }
    val close: ImageVector by lazy { icon("close", "M6 6l12 12M18 6L6 18") }
    val alert: ImageVector by lazy { icon("alert", "M12 3.8L2.8 19.8h18.4zM12 10v4.2M12 17h.01") }
    val share: ImageVector by lazy { icon("share", "M12 15V4M8 7.5L12 4l4 3.5M5 12v8h14v-8") }
    val compass: ImageVector by lazy { icon("compass", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0zM15.8 8.2l-2.1 5.5-5.5 2.1 2.1-5.5z") }
    val download: ImageVector by lazy { icon("download", "M12 4v11M8 11.5l4 4 4-4M5 20h14") }
    val bulb: ImageVector by lazy { icon("bulb", "M9 18.5h6M10 21.5h4M12 3a6 6 0 0 0-3.6 10.8c.7.5 1.1 1.3 1.1 2.2h5c0-.9.4-1.7 1.1-2.2A6 6 0 0 0 12 3z") }
    val image: ImageVector by lazy { icon("image", "M5.5 5h13a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2zM7.5 10a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0zM20.5 15.5l-4.2-4.2L7 19") }
    val heart: ImageVector by lazy { icon("heart", "M12 20.5S3.8 15.6 3.8 9.6A4.6 4.6 0 0 1 12 7a4.6 4.6 0 0 1 8.2 2.6c0 6-8.2 10.9-8.2 10.9z") }
    val settings: ImageVector by lazy { icon("settings", "M4 7h9M19 7h1M4 17h1M11 17h9M13.5 7a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0zM5.5 17a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z") }
    val trend: ImageVector by lazy { icon("trend", "M3 17l6-6 4 4 8-8M15 7h6v6") }
    val smile: ImageVector by lazy { icon("smile", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0zM8.3 14.2c.9 1.4 2.1 2.1 3.7 2.1s2.8-.7 3.7-2.1M9 9.5h.01M15 9.5h.01") }
    val meh: ImageVector by lazy { icon("meh", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0zM8.5 15.5h7M9 9.5h.01M15 9.5h.01") }
    val frown: ImageVector by lazy { icon("frown", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0zM8.3 16.3c.9-1.4 2.1-2.1 3.7-2.1s2.8.7 3.7 2.1M9 9.5h.01M15 9.5h.01") }
    val plug: ImageVector by lazy { icon("plug", "M9 3v5M15 3v5M6 8h12v3.2a6 6 0 0 1-12 0zM12 17.2V21") }
    val palette: ImageVector by lazy { icon("palette", "M12 3a9 9 0 1 0 0 18c1.2 0 1.7-.8 1.7-1.5 0-.7-.5-1.1-.5-1.8 0-.9.7-1.5 1.6-1.5H17a4 4 0 0 0 4-4c0-5-4-9.2-9-9.2zM7.5 11.5h.01M10 7.5h.01M15 7.5h.01") }
    val utensils: ImageVector by lazy { icon("utensils", "M6.5 3v6.2a2.5 2.5 0 0 0 5 0V3M9 3v18M17.5 21V3c-2.7 1.4-4 4-4 7s1.4 4.2 4 4.2") }
    val scale: ImageVector by lazy { icon("scale", "M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1 -3 3h-10a3 3 0 0 1 -3 -3v-10a3 3 0 0 1 3 -3zM7.5 10.5a6 5.2 0 0 1 9 0M12 10.5l1.8-2.4") }
    val glass: ImageVector by lazy { icon("glass", "M6.8 3.5h10.4l-1.3 16.7a1.2 1.2 0 0 1-1.2 1.1H9.3a1.2 1.2 0 0 1-1.2-1.1zM7.2 9h9.6") }
    val pin: ImageVector by lazy { icon("pin", "M12 21s6.5-5.6 6.5-11a6.5 6.5 0 0 0-13 0c0 5.4 6.5 11 6.5 11zM9.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z") }
    val skull: ImageVector by lazy { icon("skull", "M12 3a8 8 0 0 0-8 8c0 2.7 1.3 4.6 3.2 5.8V20h9.6v-3.2C18.7 15.6 20 13.700 20 11a8 8 0 0 0-8-8zM10 17v3M14 17v3M7.9 11.5a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0zM13.9 11.5a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z") }
    val mountain: ImageVector by lazy { icon("mountain", "M3 19.5L9.5 8l4 7 2.5-3.5 5 8z") }
    val stop: ImageVector by lazy { icon("stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z") }
    val edit: ImageVector by lazy { icon("edit", "M4 20l1-4.2L16.3 4.5a2.1 2.1 0 0 1 3 3L8 18.8zM14.5 6.5l3 3") }
    val skip: ImageVector by lazy { icon("skip", "M6 5.5l9 6.5-9 6.5zM18.5 5v14") }
    val play: ImageVector by lazy { icon("play", "M7.5 4.5l11 7.5-11 7.5z") }
    val chevron_right: ImageVector by lazy { icon("chevron_right", "M9.5 5.5l6.5 6.5-6.5 6.5") }
    val chevron_left: ImageVector by lazy { icon("chevron_left", "M14.5 5.5L8 12l6.5 6.5") }
    val arrow_right: ImageVector by lazy { icon("arrow_right", "M4.5 12h15M13.5 6l6 6-6 6") }
    val plus: ImageVector by lazy { icon("plus", "M12 5v14M5 12h14") }
    val minus: ImageVector by lazy { icon("minus", "M5 12h14") }
    val user: ImageVector by lazy { icon("user", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM4.5 21c.6-4 3.5-6.5 7.5-6.5s6.9 2.5 7.5 6.5") }
    val search: ImageVector by lazy { icon("search", "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0zM15.5 15.5L21 21") }
    val target: ImageVector by lazy { icon("target", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0zM7 12a5 5 0 1 0 10 0a5 5 0 1 0 -10 0zM12 12h.01") }
    val list: ImageVector by lazy { icon("list", "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01") }

    /** Name → icon, used to write icons inside strings as :name: (e.g. ":flame: 5 días"). */
    val byName: Map<String, ImageVector> by lazy {
        mapOf(
            "home" to home,
            "train" to train,
            "map" to map,
            "life" to life,
            "community" to community,
            "coin" to coin,
            "flame" to flame,
            "check" to check,
            "check_circle" to check_circle,
            "lock" to lock,
            "trophy" to trophy,
            "gift" to gift,
            "timer" to timer,
            "sparkles" to sparkles,
            "award" to award,
            "pulse" to pulse,
            "moon" to moon,
            "phone" to phone,
            "phone_off" to phone_off,
            "droplet" to droplet,
            "headphones" to headphones,
            "pause" to pause,
            "camera" to camera,
            "flip" to flip,
            "battery" to battery,
            "square" to square,
            "clipboard" to clipboard,
            "calendar" to calendar,
            "swords" to swords,
            "shield" to shield,
            "bolt" to bolt,
            "close" to close,
            "alert" to alert,
            "share" to share,
            "compass" to compass,
            "download" to download,
            "bulb" to bulb,
            "image" to image,
            "heart" to heart,
            "settings" to settings,
            "trend" to trend,
            "smile" to smile,
            "meh" to meh,
            "frown" to frown,
            "plug" to plug,
            "palette" to palette,
            "utensils" to utensils,
            "scale" to scale,
            "glass" to glass,
            "pin" to pin,
            "skull" to skull,
            "mountain" to mountain,
            "stop" to stop,
            "edit" to edit,
            "skip" to skip,
            "play" to play,
            "chevron_right" to chevron_right,
            "chevron_left" to chevron_left,
            "arrow_right" to arrow_right,
            "plus" to plus,
            "minus" to minus,
            "user" to user,
            "search" to search,
            "target" to target,
            "list" to list,
        )
    }
}
