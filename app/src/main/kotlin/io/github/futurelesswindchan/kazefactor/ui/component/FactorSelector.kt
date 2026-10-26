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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.model.FactorCategory
import io.github.futurelesswindchan.kazefactor.ui.theme.success
import io.github.futurelesswindchan.kazefactor.ui.theme.surface3
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary
import io.github.futurelesswindchan.kazefactor.ui.theme.warning

/**
 * 因子选择状态
 */
enum class FactorSelectionState {
    NONE,       // 未选中
    WANTED,     // 想要
    UNWANTED,   // 不想要
    OPTIONAL    // 可选
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
    val borderColor: Color
    val backgroundColor: Color
    
    when (selectionState) {
        FactorSelectionState.WANTED -> {
            borderColor = MaterialTheme.colorScheme.success
            backgroundColor = MaterialTheme.colorScheme.success.copy(alpha = 0.1f)
        }
        FactorSelectionState.UNWANTED -> {
            borderColor = MaterialTheme.colorScheme.error
            backgroundColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
        }
        FactorSelectionState.OPTIONAL -> {
            borderColor = MaterialTheme.colorScheme.warning
            backgroundColor = MaterialTheme.colorScheme.warning.copy(alpha = 0.1f)
        }
        FactorSelectionState.NONE -> {
            borderColor = MaterialTheme.colorScheme.outline
            backgroundColor = MaterialTheme.colorScheme.surface
        }
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
        // 图标/状态指示器
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface3),
            contentAlignment = Alignment.Center
        ) {
            when (selectionState) {
                FactorSelectionState.WANTED -> Icon(
                    Icons.Default.Check,
                    contentDescription = "想要",
                    tint = MaterialTheme.colorScheme.success
                )
                FactorSelectionState.UNWANTED -> Icon(
                    Icons.Default.Close,
                    contentDescription = "不想要",
                    tint = MaterialTheme.colorScheme.error
                )
                FactorSelectionState.OPTIONAL -> Icon(
                    Icons.Default.Star,
                    contentDescription = "可选",
                    tint = MaterialTheme.colorScheme.warning
                )
                FactorSelectionState.NONE -> Text(
                    "🎲",
                    style = MaterialTheme.typography.bodyLarge
                )
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
