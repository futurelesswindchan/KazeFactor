package io.github.futurelesswindchan.kazefactor.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.domain.rule.Violation
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary
import io.github.futurelesswindchan.kazefactor.ui.theme.warning

/**
 * 规则违规提示卡片
 * 
 * 用于显示配方验证结果（错误或警告），并提供自动修复按钮（如果支持）
 * 
 * @param violation 违规项
 * @param onAutoFix 自动修复回调（仅对支持自动修复的违规项显示按钮）
 * @param modifier 修饰符
 */
@Composable
fun RuleViolationCard(
    violation: Violation,
    onAutoFix: ((Violation) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isError = violation.isBlocking
    
    val colorScheme = if (isError) {
        Triple(
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.error,
            Icons.Default.Error
        )
    } else {
        Triple(
            MaterialTheme.colorScheme.warning.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.warning,
            Icons.Default.Warning
        )
    }
    
    val backgroundColor = colorScheme.first
    val iconColor = colorScheme.second
    val icon = colorScheme.third
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 图标
            Icon(
                imageVector = icon,
                contentDescription = if (isError) "错误" else "警告",
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            
            // 内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 违规类型标题
                Text(
                    text = getViolationTitle(violation),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // 建议文本
                Text(
                    text = getViolationSuggestion(violation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textTertiary
                )
                
                // 自动修复按钮（仅对支持自动修复的错误显示）
                if (isError && onAutoFix != null && canAutoFix(violation)) {
                    Button(
                        onClick = { onAutoFix(violation) },
                        modifier = Modifier.padding(top = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = iconColor
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "自动修复",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("一键修复")
                    }
                }
            }
        }
    }
}

/**
 * 获取违规类型的标题
 */
private fun getViolationTitle(violation: Violation): String {
    return when (violation) {
        is Violation.TooManyWanted -> "想要的因子过多"
        is Violation.WantedUnwantedOverlap -> "想要与不想要重叠"
        is Violation.ConflictInWanted -> "想要的因子冲突"
        is Violation.InsufficientPool -> "可用因子不足"
        is Violation.ImpossibleCombination -> "不可能的组合"
        is Violation.LowProbability -> "命中概率较低"
    }
}

/**
 * 获取违规建议文本
 */
private fun getViolationSuggestion(violation: Violation): String {
    return when (violation) {
        is Violation.TooManyWanted -> violation.suggestion
        is Violation.WantedUnwantedOverlap -> violation.suggestion
        is Violation.ConflictInWanted -> violation.suggestion
        is Violation.InsufficientPool -> violation.suggestion
        is Violation.ImpossibleCombination -> violation.suggestion
        is Violation.LowProbability -> violation.suggestion
    }
}

/**
 * 判断违规项是否支持自动修复
 */
private fun canAutoFix(violation: Violation): Boolean {
    return when (violation) {
        is Violation.TooManyWanted,
        is Violation.WantedUnwantedOverlap,
        is Violation.ConflictInWanted,
        is Violation.InsufficientPool -> true
        is Violation.ImpossibleCombination,
        is Violation.LowProbability -> false
    }
}
