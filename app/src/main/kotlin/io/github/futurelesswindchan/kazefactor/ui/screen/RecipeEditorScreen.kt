package io.github.futurelesswindchan.kazefactor.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.domain.rule.Violation
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeEditorViewModel
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelectionState
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelector
import io.github.futurelesswindchan.kazefactor.ui.component.RuleViolationCard
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary
import io.github.futurelesswindchan.kazefactor.ui.theme.warning
import kotlinx.coroutines.launch

/**
 * 配方编辑界面（已优化：消除布局抖动、整合概率警告）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(
    viewModel: RecipeEditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToRecipeCode: (String) -> Unit = {}
) {
    val recipeName by viewModel.recipeName.collectAsState()
    val factorSelections by viewModel.factorSelections.collectAsState()
    val minOptional by viewModel.minOptional.collectAsState()
    val violations by viewModel.violations.collectAsState()
    val hasBlockingErrors by viewModel.hasBlockingErrors.collectAsState()
    val probability by viewModel.probability.collectAsState()
    val currentRecipe by viewModel.currentRecipe.collectAsState()
    
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // 过滤非阻塞性警告（低概率），这些将整合到 InfoCard 中
    val blockingViolations = violations.filter { it.isBlocking }
    val warningViolations = violations.filter { !it.isBlocking }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentRecipe.id.isEmpty()) "新建配方" else "编辑配方") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onNavigateToRecipeCode(currentRecipe.id) },
                        enabled = !hasBlockingErrors
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                viewModel.saveRecipe().onSuccess {
                                    snackbarHostState.showSnackbar("保存成功")
                                    onNavigateBack()
                                }.onFailure {
                                    snackbarHostState.showSnackbar("保存失败：${it.message}")
                                }
                            }
                        },
                        enabled = !hasBlockingErrors && recipeName.isNotBlank()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "保存")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 配方名称输入
            OutlinedTextField(
                value = recipeName,
                onValueChange = { viewModel.updateRecipeName(it) },
                label = { Text("配方名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            
            // 整合型信息栏（消除抖动）
            InfoCard(
                probability = probability,
                hasWarnings = warningViolations.isNotEmpty()
            )
            
            // 只有真正阻塞的错误才单独显示（它们确实需要修复，必须显示）
            if (blockingViolations.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    blockingViolations.forEach { violation ->
                        RuleViolationCard(
                            violation = violation,
                            onAutoFix = { viewModel.autoFixViolation(it) }
                        )
                    }
                }
            }
            
            // 可选因子最小数量控制区
            val optionalCount = factorSelections.count { it.value == FactorSelectionState.OPTIONAL }
            AnimatedVisibility(
                visible = optionalCount > 0,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "可选因子最小数量：$minOptional 个",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = minOptional.toFloat(),
                            onValueChange = { viewModel.updateMinOptional(it.toInt()) },
                            valueRange = 0f..optionalCount.toFloat(),
                            steps = optionalCount - 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "从 $optionalCount 个可选因子中，至少要出现 $minOptional 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 因子选择区（使用 weight 填满剩余空间，独立滚动）
            Text(
                text = "选择因子",
                style = MaterialTheme.typography.headlineSmall
            )
            FactorSelector(
                factorsByCategory = viewModel.getFactorsByCategory(),
                selectionStates = factorSelections,
                onFactorClick = { factorId, _ ->
                    viewModel.toggleFactorSelection(factorId)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 整合型信息栏（命中概率 + 警告标识）
 */
@Composable
private fun InfoCard(probability: Double, hasWarnings: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "命中概率",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (probability > 0.0) "${"%.2f".format(probability * 100)}%" else "—",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            if (hasWarnings) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "警告",
                        tint = MaterialTheme.colorScheme.warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "概率较低",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}
