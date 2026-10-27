package io.github.futurelesswindchan.kazefactor.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
 * 因子选择器
 * 
 * @param factorsByCategory 按类别分组的因子列表（Map<类别名, List<Factor>>）
 * @param selectionStates 当前选中状态（Map<因子ID, 选择状态>）
 * @param onFactorClick 因子点击回调（参数：因子ID，当前状态）
 * @param modifier 修饰符
 */
@Composable
fun FactorSelector(
    factorsByCategory: Map<String, List<Factor>>,
    selectionStates: Map<Int, FactorSelectionState>,
    onFactorClick: (factorId: Int, currentState: FactorSelectionState) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 按类别分组展示
        factorsByCategory.forEach { (categoryName, factors) ->
            // 类别标题
            item(key = "header_$categoryName") {
                CategoryHeader(
                    categoryName = getCategoryDisplayName(categoryName),
                    factorCount = factors.size
                )
            }
            
            // 因子列表
            items(
                items = factors,
                key = { it.id }
            ) { factor ->
                val state = selectionStates[factor.id] ?: FactorSelectionState.NONE
                FactorItem(
                    factor = factor,
                    selectionState = state,
                    onClick = { onFactorClick(factor.id, state) }
                )
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
    factorCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = categoryName,
            style = MaterialTheme.typography.headlineSmall,
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
 * 单个因子项
 */
@Composable
private fun FactorItem(
    factor: Factor,
    selectionState: FactorSelectionState,
    onClick: () -> Unit
) {
    val factorPainter = FactorIconMapper.rememberFactorPainter(factor)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selectionState == FactorSelectionState.NONE) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = 2.dp,
                color = when (selectionState) {
                    FactorSelectionState.WANTED -> MaterialTheme.colorScheme.success
                    FactorSelectionState.UNWANTED -> MaterialTheme.colorScheme.error
                    FactorSelectionState.NONE -> MaterialTheme.colorScheme.outline
                },
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
            // 圆形底座 + 真实因子图标
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface3),
                contentAlignment = Alignment.Center
            ) {
                if (factorPainter != null) {
                    androidx.compose.foundation.Image(
                        painter = factorPainter,
                        contentDescription = factor.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(34.dp)
                    )
                } else {
                    Text("🎲", style = MaterialTheme.typography.bodyLarge)
                }
            }

            // 状态角标（右下角）
            if (selectionState != FactorSelectionState.NONE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
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
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
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
 * 将类别枚举名转换为中文显示名
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
