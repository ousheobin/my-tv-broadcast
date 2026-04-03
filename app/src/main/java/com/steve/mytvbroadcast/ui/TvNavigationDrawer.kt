package com.steve.mytvbroadcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.SignalSource

@Composable
fun TvNavigationDrawer(
    sources: List<SignalSource>,
    selectedSourceId: String,
    onSourceSelected: (SignalSource) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(0xFF121318))
            .padding(vertical = 16.dp)
    ) {
        // Source items
        sources.forEach { source ->
            val isSelected = source.id == selectedSourceId
            DrawerItem(
                text = source.name,
                isSelected = isSelected,
                onClick = { onSourceSelected(source) }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        HorizontalDivider(
            color = Color(0xFF44464F),
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Settings button at bottom
        DrawerItem(
            text = "设置",
            isSelected = false,
            onClick = onSettingsClick,
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_settings),
                    contentDescription = "设置",
                    tint = Color(0xFFBFC6DC),
                    modifier = Modifier.size(24.dp)
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DrawerItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null
) {
    val backgroundColor = if (isSelected) Color(0xFF2D4678) else Color.Transparent
    val textColor = if (isSelected) Color(0xFFD9E2FF) else Color(0xFFBFC6DC)

    val contentPadding = if (icon != null) 12.dp else androidx.compose.ui.unit.Dp(0f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(start = contentPadding, top = 12.dp, end = contentPadding, bottom = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = text,
                color = textColor,
                fontSize = 16.sp
            )
        }
    }
}
