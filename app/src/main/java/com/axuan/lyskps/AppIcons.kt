package com.axuan.lyskps

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object AppIcons {
    val Launch: ImageVector by lazy {
        ImageVector.Builder(
            name = "Launch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 5.2f)
                curveTo(8f, 4.18f, 9.12f, 3.56f, 9.98f, 4.11f)
                lineTo(20.7f, 10.91f)
                curveTo(21.5f, 11.42f, 21.5f, 12.58f, 20.7f, 13.09f)
                lineTo(9.98f, 19.89f)
                curveTo(9.12f, 20.44f, 8f, 19.82f, 8f, 18.8f)
                close()
            }
        }.build()
    }

    val Home: ImageVector by lazy {
        ImageVector.Builder(
            name = "Home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 3f)
                lineTo(2f, 12f)
                horizontalLineTo(5f)
                verticalLineTo(21f)
                horizontalLineTo(11f)
                verticalLineTo(15f)
                horizontalLineTo(13f)
                verticalLineTo(21f)
                horizontalLineTo(19f)
                verticalLineTo(12f)
                horizontalLineTo(22f)
                close()
            }
        }.build()
    }

    val Proxy: ImageVector by lazy {
        ImageVector.Builder(
            name = "Proxy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 7f)
                verticalLineTo(4f)
                lineTo(2f, 9f)
                lineTo(7f, 14f)
                verticalLineTo(11f)
                horizontalLineTo(16f)
                verticalLineTo(7f)
                close()
                moveTo(17f, 10f)
                verticalLineTo(13f)
                horizontalLineTo(8f)
                verticalLineTo(17f)
                horizontalLineTo(17f)
                verticalLineTo(20f)
                lineTo(22f, 15f)
                close()
            }
        }.build()
    }

    val Security: ImageVector by lazy {
        ImageVector.Builder(
            name = "Security",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(3f, 6f)
                verticalLineTo(12f)
                curveTo(3f, 17.55f, 6.84f, 22.74f, 12f, 24f)
                curveTo(17.16f, 22.74f, 21f, 17.55f, 21f, 12f)
                verticalLineTo(6f)
                close()
                moveTo(12f, 4.18f)
                lineTo(19f, 7.3f)
                verticalLineTo(12f)
                curveTo(19f, 16.35f, 16.16f, 20.42f, 12f, 21.72f)
                close()
            }
        }.build()
    }

    val Logs: ImageVector by lazy {
        ImageVector.Builder(
            name = "Logs",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(5f, 2f)
                lineTo(7f, 3.5f)
                lineTo(9f, 2f)
                lineTo(11f, 3.5f)
                lineTo(13f, 2f)
                lineTo(15f, 3.5f)
                lineTo(17f, 2f)
                lineTo(19f, 3.5f)
                verticalLineTo(22f)
                lineTo(17f, 20.5f)
                lineTo(15f, 22f)
                lineTo(13f, 20.5f)
                lineTo(11f, 22f)
                lineTo(9f, 20.5f)
                lineTo(7f, 22f)
                lineTo(5f, 20.5f)
                close()
                moveTo(8f, 7f)
                horizontalLineTo(16f)
                verticalLineTo(9f)
                horizontalLineTo(8f)
                close()
                moveTo(8f, 11f)
                horizontalLineTo(16f)
                verticalLineTo(13f)
                horizontalLineTo(8f)
                close()
                moveTo(8f, 15f)
                horizontalLineTo(14f)
                verticalLineTo(17f)
                horizontalLineTo(8f)
                close()
            }
        }.build()
    }
}
