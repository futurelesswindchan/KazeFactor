package io.github.futurelesswindchan.kazefactor.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.ui.theme.success
import io.github.futurelesswindchan.kazefactor.ui.theme.surface3
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary
import io.github.futurelesswindchan.kazefactor.ui.util.FactorIconMapper

/**
 * 因子选择状态
 *
 * 未选中（NONE）即代表默认可选/不限制；点击循环：未选中 -> 想要 -> 不想要 -> 未选中
 */
enum class FactorSelectionState {
    NONE,       // 默认（可选/不限制）
    WANTED,     // 想要（必出）
    UNWANTED    // 不想要（排除）
}

/**
 * 因子选择器排布模式
 */
enum class FactorDisplayMode {
    LIST,       // 详细长条卡片列表
    GRID        // 紧凑纯图标网格
}

/**
 * 因子选择器（支持列表模式与紧凑图标网格模式切换）
 * 
 * @param factorsByCategory 按类别分组的因子列表（Map<类别名, List<Factor>>）
 * @param selectionStates 当前选中状态（Map<因子ID, 选择状态>）
 * @param onFactorClick 因子点击回调（参数：因子ID，当前状态）
 * @param displayMode 显示模式（列表 / 紧凑网格）
 * @param modifier 修饰符
 */
@Composable
fun FactorSelector(
    factorsByCategory: Map<String, List<Factor>>,
    selectionStates: Map<Int, FactorSelectionState>,
    onFactorClick: (factorId: Int, currentState: FactorSelectionState) -> Unit,
    displayMode: FactorDisplayMode = FactorDisplayMode.LIST,
    modifier: Modifier = Modifier
) {
    when (displayMode) {
        FactorDisplayMode.LIST -> {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                factorsByCategory.forEach { (categoryName, factors) ->
                    item(key = "header_$categoryName") {
                        CategoryHeader(
                            categoryName = getCategoryDisplayName(categoryName),
                            factorCount = factors.size,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    items(
                        items = factors,
                        key = { it.id }
                    ) { factor ->
                        val state = selectionStates[factor.id] ?: FactorSelectionState.NONE
                        FactorListItem(
                            factor = factor,
                            selectionState = state,
                            onClick = { onFactorClick(factor.id, state) }
                        )
                    }
                }
            }
        }

        FactorDisplayMode.GRID -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 56.dp),
                modifier = modifier,
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                factorsByCategory.forEach { (categoryName, factors) ->
                    item(
                        key = "grid_header_$categoryName",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        CategoryHeader(
                            categoryName = getCategoryDisplayName(categoryName),
                            factorCount = factors.size,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }

                    items(
                        items = factors,
                        key = { it.id }
                    ) { factor ->
                        val state = selectionStates[factor.id] ?: FactorSelectionState.NONE
                        FactorGridItem(
                            factor = factor,
                            selectionState = state,
                            onClick = { onFactorClick(factor.id, state) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 类别标题
 */
@Composable
private fun CategoryHeader(
    categoryName: String,
    factorCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = categoryName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "$factorCount 个因子",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.textTertiary
        )
    }
}

/**
 * 列表模式：单个长条因子卡片
 */
@Composable
private fun FactorListItem(
    factor: Factor,
    selectionState: FactorSelectionState,
    onClick: () -> Unit
) {
    val factorPainter = FactorIconMapper.rememberFactorPainter(factor)

    val borderColor = when (selectionState) {
        FactorSelectionState.WANTED -> MaterialTheme.colorScheme.success
        FactorSelectionState.UNWANTED -> MaterialTheme.colorScheme.error
        FactorSelectionState.NONE -> MaterialTheme.colorScheme.outline
    }

    val backgroundColor = when (selectionState) {
        FactorSelectionState.WANTED -> MaterialTheme.colorScheme.success.copy(alpha = 0.1f)
        FactorSelectionState.UNWANTED -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
        FactorSelectionState.NONE -> MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标容器（带状态角标）
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface3),
                contentAlignment = Alignment.Center
            ) {
                if (factorPainter != null) {
                    Image(
                        painter = factorPainter,
                        contentDescription = factor.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(34.dp)
                    )
                } else {
                    Text("🎲", style = MaterialTheme.typography.bodyLarge)
                }
            }

            SelectionBadge(
                selectionState = selectionState,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }

        // 因子信息
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = factor.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = factor.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 紧凑网格模式：只显示图标，选中/不选中通过边框与底色变色表示
 */
@Composable
private fun FactorGridItem(
    factor: Factor,
    selectionState: FactorSelectionState,
    onClick: () -> Unit
) {
    val factorPainter = FactorIconMapper.rememberFactorPainter(factor)

    val borderColor = when (selectionState) {
        FactorSelectionState.WANTED -> MaterialTheme.colorScheme.success
        FactorSelectionState.UNWANTED -> MaterialTheme.colorScheme.error
        FactorSelectionState.NONE -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }

    val borderWidth = if (selectionState == FactorSelectionState.NONE) 1.5.dp else 2.5.dp

    val backgroundColor = when (selectionState) {
        FactorSelectionState.WANTED -> MaterialTheme.colorScheme.success.copy(alpha = 0.16f)
        FactorSelectionState.UNWANTED -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        FactorSelectionState.NONE -> MaterialTheme.colorScheme.surface3
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        if (factorPainter != null) {
            Image(
                painter = factorPainter,
                contentDescription = factor.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(0.78f)
            )
        } else {
            Text("🎲", style = MaterialTheme.typography.bodyLarge)
        }

        SelectionBadge(
            selectionState = selectionState,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

/**
 * 右下角选中状态小角标（想要 ✓ / 不想要 ✗）
 */
@Composable
private fun SelectionBadge(
    selectionState: FactorSelectionState,
    modifier: Modifier = Modifier
) {
    if (selectionState != FactorSelectionState.NONE) {
        Box(
            modifier = modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(
                    when (selectionState) {
                        FactorSelectionState.WANTED -> MaterialTheme.colorScheme.success
                        FactorSelectionState.UNWANTED -> MaterialTheme.colorScheme.error
                        else -> Color.Transparent
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (selectionState == FactorSelectionState.WANTED) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

/**
 * 小巧紧凑型因子图标块（用于编辑页置顶已选栏与主界面配方卡片预览）
 *
 * @param factor 挑战因子
 * @param selectionState 选中状态（WANTED 绿框 / UNWANTED 红框）
 * @param size 图标框尺寸
 * @param onClick 点击回调（可选）
 */
@Composable
fun CompactFactorChip(
    factor: Factor,
    selectionState: FactorSelectionState,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: (() -> Unit)? = null
) {
    val factorPainter = FactorIconMapper.rememberFactorPainter(factor)

    val borderColor = when (selectionState) {
        FactorSelectionState.WANTED -> MaterialTheme.colorScheme.success
        FactorSelectionState.UNWANTED -> MaterialTheme.colorScheme.error
        FactorSelectionState.NONE -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }

    val backgroundColor = when (selectionState) {
        FactorSelectionState.WANTED -> MaterialTheme.colorScheme.success.copy(alpha = 0.16f)
        FactorSelectionState.UNWANTED -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        FactorSelectionState.NONE -> MaterialTheme.colorScheme.surface3
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(5.dp),
        contentAlignment = Alignment.Center
    ) {
        if (factorPainter != null) {
            Image(
                painter = factorPainter,
                contentDescription = factor.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(0.8f)
            )
        } else {
            Text("🎲", style = MaterialTheme.typography.bodySmall)
        }

        SelectionBadge(
            selectionState = selectionState,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

/**
 * 获取类别的中文显示名称
 */
private fun getCategoryDisplayName(categoryName: String): String {
    return when (categoryName) {
        "MAJOR_BAD" -> "大恶因子"
        "MODERATE_BAD" -> "中恶因子"
        "MINOR_BAD" -> "小恶因子"
        "NEUTRAL" -> "中性因子"
        "MINOR_GOOD" -> "小良因子"
        "MAJOR_GOOD" -> "大良因子"
        else -> categoryName
    }
}
